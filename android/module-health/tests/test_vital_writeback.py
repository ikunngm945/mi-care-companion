"""Exercise the production vital writeback SQL on synthetic SQLite rows."""
import json
import re
import sqlite3
import unittest
from pathlib import Path


SOURCE = Path(__file__).resolve().parents[1] / 'cn/local/micompanion/health/VitalWriteback.java'
METRICS = (
    ('hr_record', 'single_heart_rate', 'bpm', 74, 83),
    ('spo2_record', 'spo2', 'spo2', 98, 94),
)


class VitalWritebackTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = {
            name: ''.join(re.findall(r'"([^"\n]*)"', pieces))
            for name, pieces in re.findall(r'\bString ([A-Z_]+) = ((?:"[^"\n]*"\s*\+?\s*)+);', SOURCE.read_text(encoding='utf-8'))
        }

    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.execute('CREATE TABLE draft_event (name TEXT)')
        for table, *_ in METRICS:
            self.db.executescript(f'''
                CREATE TABLE {table} (key TEXT NOT NULL, sid TEXT NOT NULL, time INTEGER NOT NULL,
                    value TEXT, isDeleted INTEGER NOT NULL, isUpload INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY(key,sid,time));
                CREATE TRIGGER _trigger_{table}_insert AFTER INSERT ON {table} FOR EACH ROW WHEN NEW.isDeleted=0
                    BEGIN INSERT INTO draft_event VALUES ('insert'); END;
                CREATE TRIGGER _trigger_{table}_update_del AFTER UPDATE OF isDeleted ON {table} FOR EACH ROW WHEN OLD.isDeleted<>NEW.isDeleted
                    BEGIN INSERT INTO draft_event VALUES ('soft_delete'); END;
                CREATE TRIGGER _trigger_{table}_hard_del AFTER DELETE  ON {table} BEGIN
                    INSERT INTO draft_event VALUES ('hard_delete'); END;
            ''')

    def query(self, name, table, field, params):
        return self.db.execute(self.sql[name].replace('%1$s', table).replace('%2$s', field), params)

    def test_single_value_compare_and_swap_and_restore(self):
        for table, key, field, before, after in METRICS:
            with self.subTest(table=table):
                identity = (key, 'synthetic', 100)
                raw = f'{{ "time":101, "{field}":{before}, "extra":{{"{field}":55,"big":9007199254740993}} }}'
                self.db.executemany(f'INSERT INTO {table}(key,sid,time,value,isDeleted) VALUES (?,?,?,?,?)', (
                    (*identity, raw, 0), (key, 'other', 100, raw, 0), (key, 'synthetic', 99, raw, 1),
                ))
                self.db.execute('DELETE FROM draft_event')
                old, = self.query('READ_ROW', table, field, identity).fetchone()
                self.assertEqual(old, raw)
                self.assertEqual(self.query('READ_PAYLOAD', table, field, (old,)).fetchone(), (1, 1, before, 101))
                self.assertEqual(self.query('UPDATE_ROW', table, field, (after, *identity, old.strip() + ' ')).rowcount, 0)
                self.assertEqual(self.query('UPDATE_ROW', table, field, (after, *identity, old)).rowcount, 1)
                current, = self.query('READ_ROW', table, field, identity).fetchone()
                self.assertEqual(json.loads(current), dict(json.loads(raw), **{field: after}))
                self.assertEqual(self.query('RESTORE_ROW', table, field, (raw, *identity, old)).rowcount, 0)
                self.assertEqual(self.query('RESTORE_ROW', table, field, (raw, key, 'synthetic', 99, old)).rowcount, 0)
                self.assertEqual(self.query('RESTORE_ROW', table, field, (raw, *identity, current)).rowcount, 1)
                self.assertEqual(self.query('READ_ROW', table, field, identity).fetchone(), (raw,))
                self.assertEqual(self.db.execute(f'SELECT value,isUpload FROM {table} WHERE sid=?', ('other',)).fetchone(), (raw, 1))
                self.assertEqual(self.db.execute(f'SELECT isUpload FROM {table} WHERE sid=? AND time=?', ('synthetic', 100)).fetchone(), (1,))
                self.assertEqual(self.db.execute('SELECT name FROM draft_event').fetchall(), [])

    def test_sql_exposes_invalid_shapes_without_coercing(self):
        for table, _, field, *_ in METRICS:
            for raw, expected in (
                (f'{{"{field}":42}}', (1, 0, 42, None)),
                (f'{{"time":101,"time":102,"{field}":42}}', (1, 2, 42, 101)),
                (f'{{"time":101,"{field}":42,"{field}":57}}', (2, 1, 42, 101)),
                (f'{{"time":"101","{field}":42}}', (1, 1, 42, None)),
                (f'{{"time":101,"{field}":"42"}}', (1, 1, None, 101)),
                (f'{{"time":101,"{field}":true}}', (1, 1, None, 101)),
                (f'[{{"time":101,"{field}":42}}]', (0, 0, None, None)),
                ('{invalid', (0, 0, None, None)),
            ):
                with self.subTest(table=table, raw=raw):
                    self.assertEqual(self.query('READ_PAYLOAD', table, field, (raw,)).fetchone(), expected)


if __name__ == '__main__':
    unittest.main()
