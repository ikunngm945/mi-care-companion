"""Run the module's actual SQL against a synthetic Xiaomi-style SQLite database."""
import json
import re
import sqlite3
import tempfile
import unittest
from pathlib import Path


SOURCE = Path(__file__).resolve().parents[1] / 'cn/local/micompanion/health/StressWriteback.java'
BEFORE = '{ "time":101, "stress":42, "untouched":{"stress":19,"big":9007199254740993} }'


class StressWritebackTest(unittest.TestCase):
    def setUp(self):
        source = SOURCE.read_text(encoding='utf-8')
        self.sql = {}
        for name, pieces in re.findall(r'\bString ([A-Z_]+) = ((?:"[^"\n]*"\s*\+?\s*)+);', source):
            self.sql[name] = ''.join(re.findall(r'"([^"\n]*)"', pieces))
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.execute('CREATE TABLE stress_record (key TEXT NOT NULL,sid TEXT NOT NULL,time INTEGER NOT NULL,'
                        'value TEXT,isDeleted INTEGER NOT NULL, isUpload INTEGER NOT NULL DEFAULT 1,'
                        'PRIMARY KEY(key,sid,time))')
        self.db.executemany('INSERT INTO stress_record(key,sid,time,value,isDeleted) VALUES(?,?,?,?,?)', (
            ('single_stress', 'fixture', 100, BEFORE, 0),
            ('stress', 'other', 100, BEFORE, 0),
            ('single_stress', 'fixture', 102, BEFORE, 1),
        ))
        self.db.commit()

    def change(self, op, before, after):
        with self.db:
            self.db.execute('BEGIN IMMEDIATE')
            identity = ('single_stress', 'fixture', 100)
            row = self.db.execute(self.sql['READ_ROW'], identity).fetchall()
            self.assertEqual(len(row), 1)
            raw = row[0][0]
            self.assertEqual(self.db.execute(self.sql['READ_PAYLOAD'], (raw,)).fetchone(), (1, 1, before, 101))
            self.db.execute(self.sql['CREATE_HISTORY'])
            self.db.execute(self.sql['CREATE_INDEX'])
            objects = self.db.execute('SELECT sql FROM sqlite_master WHERE name IN (?,?) ORDER BY type DESC',
                                      ('micompanion_stress_history', 'micompanion_stress_row_history')).fetchall()
            self.assertEqual(objects, [(self.sql[name].replace('IF NOT EXISTS ', ''),)
                                       for name in ('CREATE_HISTORY', 'CREATE_INDEX')])
            self.assertIsNone(self.db.execute(self.sql['READ_OP'], (op,)).fetchone())
            self.db.execute(self.sql['INSERT_BACKUP'], (op, 'fixture', 'single_stress', 100, 101, before, after, raw, 1700000000000))
            self.assertEqual(self.db.execute(self.sql['UPDATE_ROW'], (after, *identity, raw)).rowcount, 1)
            return raw

    def test_edit_backup_restore_and_replication(self):
        self.assertEqual(self.change('op-one', 42, 78), BEFORE)
        changed = self.db.execute('SELECT value,isUpload FROM stress_record WHERE sid=? AND time=?', ('fixture', 100)).fetchone()
        self.assertEqual(json.loads(changed[0]), {'time': 101, 'stress': 78, 'untouched': {'stress': 19, 'big': 9007199254740993}})
        self.assertEqual(changed[1], 1)
        self.assertEqual(self.db.execute(self.sql['READ_OP'], ('op-one',)).fetchone(), ('fixture', 'single_stress', 100, 101, 42, 78))
        self.assertEqual(self.change('op-two', 78, 42), changed[0])
        self.assertEqual(self.db.execute('SELECT backup FROM micompanion_stress_history ORDER BY seq').fetchall(), [(BEFORE,), (changed[0],)])
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM stress_record WHERE value=? AND NOT (sid=? AND time=?)',
                                         (BEFORE, 'fixture', 100)).fetchone()[0], 2)
        with tempfile.TemporaryDirectory() as tmp:
            copy = Path(tmp) / 'replica.db'
            self.db.execute('VACUUM INTO ?', (str(copy),))
            with sqlite3.connect(copy.as_uri() + '?mode=ro', uri=True) as replica:
                self.assertEqual(replica.execute('SELECT before_value,after_value FROM micompanion_stress_history ORDER BY seq').fetchall(),
                                 [(42, 78), (78, 42)])

    def test_native_draft_triggers_ignore_value_writeback(self):
        self.db.executescript("""
            CREATE TABLE draft_events (operation TEXT, sid TEXT);
            CREATE TRIGGER _trigger_stress_record_insert AFTER INSERT ON stress_record FOR EACH ROW WHEN NEW.isDeleted=0
            BEGIN INSERT INTO draft_events VALUES ('insert', NEW.sid); END;
            CREATE TRIGGER _trigger_stress_record_update_del AFTER UPDATE OF isDeleted ON stress_record FOR EACH ROW WHEN OLD.isDeleted<>NEW.isDeleted
            BEGIN INSERT INTO draft_events VALUES ('soft_delete', NEW.sid); END;
            CREATE TRIGGER _trigger_stress_record_hard_del AFTER DELETE  ON stress_record BEGIN
                INSERT INTO draft_events VALUES ('hard_delete', OLD.sid);
            END;
        """)
        self.assertEqual(self.change('op-native', 42, 78), BEFORE)
        self.assertEqual(self.db.execute('SELECT operation FROM draft_events').fetchall(), [])
        self.assertEqual(self.db.execute(self.sql['READ_OP'], ('op-native',)).fetchone(),
                         ('fixture', 'single_stress', 100, 101, 42, 78))

        # Positive controls: each event is observable when SQLite dispatches its native operation.
        self.db.execute("INSERT INTO stress_record(key,sid,time,value,isDeleted) VALUES ('stress','control',100,?,0)",
                        (BEFORE,))
        self.db.execute("UPDATE stress_record SET isDeleted=1 WHERE sid='control'")
        self.db.execute("DELETE FROM stress_record WHERE sid='control'")
        self.assertEqual(self.db.execute('SELECT operation,sid FROM draft_events ORDER BY rowid').fetchall(),
                         [('insert', 'control'), ('soft_delete', 'control'), ('hard_delete', 'control')])

    def test_value_sensitive_triggers_would_fire_during_writeback(self):
        # These must stay outside the allow-list: the production UPDATE would dispatch them.
        for trigger in (
            '_trigger_stress_record_update_del AFTER UPDATE OF isDeleted,value ON stress_record FOR EACH ROW WHEN 1',
            'fixture_unknown AFTER UPDATE ON stress_record',
        ):
            with self.subTest(trigger=trigger):
                self.db.execute(f"CREATE TRIGGER {trigger} BEGIN SELECT RAISE(ABORT, 'unexpected draft'); END")
                try:
                    with self.assertRaisesRegex(sqlite3.IntegrityError, 'unexpected draft'):
                        self.change('op-unsafe', 42, 78)
                finally:
                    self.db.execute('DROP TRIGGER ' + trigger.split()[0])
        self.assertIsNone(self.db.execute("SELECT 1 FROM sqlite_master WHERE name='micompanion_stress_history'").fetchone())

    def test_failed_update_rolls_back_backup(self):
        self.db.execute("CREATE TRIGGER fixture_fail BEFORE UPDATE ON stress_record BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        self.db.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            self.change('op-abort', 42, 78)
        self.assertIsNone(self.db.execute("SELECT 1 FROM sqlite_master WHERE name='micompanion_stress_history'").fetchone())
        self.db.execute(self.sql['CREATE_HISTORY'])
        with self.assertRaises(sqlite3.IntegrityError):
            self.change('op-abort-again', 42, 78)
        self.assertEqual(self.db.execute('SELECT count(*) FROM micompanion_stress_history').fetchone(), (0,))
        self.assertEqual(self.db.execute('SELECT value FROM stress_record WHERE sid=? AND time=?', ('fixture', 100)).fetchone(), (BEFORE,))

    def test_read_checks_distinguish_missing_duplicate_or_wrong_type(self):
        for raw, expected in (
            ('{"stress":42}', (1, 0, 42, None)),
            ('{"time":101,"stress":42,"stress":17}', (2, 1, 42, 101)),
            ('{"time":null,"stress":"42"}', (1, 1, None, None)),
            ('{"time":101,"stress":true}', (1, 1, None, 101)),
            ('{invalid', (0, 0, None, None)),
        ):
            with self.subTest(raw=raw):
                self.assertEqual(self.db.execute(self.sql['READ_PAYLOAD'], (raw,)).fetchone(), expected)
        self.assertEqual(self.db.execute(self.sql['READ_ROW'], ('single_stress', 'fixture', 102)).fetchall(), [])


if __name__ == '__main__':
    unittest.main()
