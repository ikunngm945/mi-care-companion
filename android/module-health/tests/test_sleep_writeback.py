"""Synthetic source only: exercise SleepWriteback's SQLite statements against an in-memory database."""
import json
import re
import sqlite3
import subprocess
import tempfile
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / 'cn/local/micompanion/health/SleepWriteback.java'
RAW = '{ "bedtime": 101, "wake_up_time": 333, "items": [{"start_time": 101, "end_time": 222, "state": 3, "opaque":{"counter":900719925474099312345}}, {"start_time":222,"end_time":333,"state":6}], "vendor": [1e999, -0, null] }'


class SleepWritebackSqlTest(unittest.TestCase):
    def setUp(self):
        source = SOURCE.read_text(encoding='utf-8')
        self.sql = {}
        for name, pieces in re.findall(r'\bString ([A-Z_]+) = ((?:"[^"\n]*"\s*\+?\s*)+);', source):
            self.sql[name] = ''.join(re.findall(r'"([^"\n]*)"', pieces))
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)

    def patch(self, raw, path, value):
        return self.db.execute(self.sql['REPLACE_MEMBER'], (raw, path, value)).fetchone()[0]

    def test_phase_patch_preserves_unknown_members_without_numeric_coercion(self):
        changed = self.patch(RAW, '$.items[0].end_time', 205)
        changed = self.patch(changed, '$.items[0].state', 2)
        self.assertEqual(json.loads(changed)['items'][0]['end_time'], 205)
        self.assertEqual(json.loads(changed)['items'][0]['state'], 2)
        self.assertEqual(json.loads(changed)['items'][1]['state'], 6)
        self.assertIn('900719925474099312345', changed)
        self.assertIn('[1e999,-0,null]', changed)

    def test_patch_only_affects_existing_unique_member(self):
        changed = self.patch(RAW, '$.items[0].missing', 7)
        self.assertNotIn('missing', json.loads(changed)['items'][0])
        self.assertIsNone(self.db.execute(self.sql['AMBIGUOUS_JSON'], (RAW,)).fetchone())
        self.assertEqual(self.db.execute(self.sql['AMBIGUOUS_JSON'], ('{"items":[{"state":2,"state":3}]}',)).fetchone(), (1,))

    def test_source_cas_and_exact_historical_restore(self):
        self.db.execute('CREATE TABLE sleep_segment(key TEXT,sid TEXT,time INTEGER,endTime INTEGER,'
                        'timeIn0Tz INTEGER,endTimeIn0Tz INTEGER,zoneOffsetInSec INTEGER,isDeleted INTEGER,'
                        'value TEXT,PRIMARY KEY(key,sid,time))')
        self.db.execute('CREATE TABLE synthetic_journal(backup TEXT)')
        row = ('sleep', 'fixture', 333, 222, 3933, 3822, 3600)
        self.db.execute('INSERT INTO sleep_segment VALUES(?,?,?,?,?,?,?,0,?)', (*row, RAW))
        self.db.execute('INSERT INTO sleep_segment VALUES(?,?,?,?,?,?,?,1,?)',
                        ('sleep', 'deleted', *row[2:], RAW))
        self.db.commit()
        changed = self.patch(RAW, '$.items[0].state', 2)
        with self.db:
            exact = self.db.execute(self.sql['READ_ROW'], row[:3]).fetchone()
            self.assertEqual(exact, (RAW, *row[3:]))
            self.db.execute('INSERT INTO synthetic_journal VALUES(?)', (exact[0],))
            self.assertEqual(self.db.execute(self.sql['UPDATE_ROW'], (changed, *row, RAW)).rowcount, 1)
            self.assertEqual(self.db.execute(self.sql['UPDATE_ROW'], (RAW, *row, RAW)).rowcount, 0)
            self.assertEqual(self.db.execute(self.sql['UPDATE_ROW'], (RAW, *row[:-1], 0, changed)).rowcount, 0)
        with self.db:
            backup = self.db.execute('SELECT backup FROM synthetic_journal').fetchone()[0]
            self.db.execute('INSERT INTO synthetic_journal VALUES(?)', (changed,))
            self.assertEqual(self.db.execute(self.sql['UPDATE_ROW'], (backup, *row, changed)).rowcount, 1)
        self.assertEqual(self.db.execute(self.sql['READ_ROW'], row[:3]).fetchone(), (RAW, *row[3:]))
        self.assertEqual(self.db.execute('SELECT backup FROM synthetic_journal').fetchall(), [(RAW,), (changed,)])
        self.assertIsNone(self.db.execute(self.sql['READ_ROW'], ('sleep', 'deleted', 333)).fetchone())


class SleepWritebackPlannerTest(unittest.TestCase):
    def test_phase_mapping_aggregates_and_refusal(self):
        root = SOURCE.parents[6]
        android = root / 'tools/deps/android.jar'
        org_json = root / 'tools/deps/json.jar'
        src = SOURCE.parent
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            fixture = work / 'Planner.java'
            fixture.write_text(PLANNER, encoding='utf-8')
            subprocess.run(['javac', '--release', '8', '-cp', str(android), '-d', directory,
                            str(fixture), *(str(src / name) for name in ('SleepWriteback.java', 'HealthWriteback.java',
                            'StressWriteback.java', 'VitalWriteback.java', 'BandFileImporter.java'))], check=True)
            subprocess.run(['java', '-cp', f'{directory}:{org_json}:{android}', 'Planner'], check=True)


PLANNER = r'''
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

public class Planner {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static Object call(Method method, Object receiver, Object... args) throws Exception {
        method.setAccessible(true);
        return method.invoke(receiver, args);
    }
    static Object make(Class<?> type, Class<?>[] signature, Object... args) throws Exception {
        Constructor<?> ctor = type.getDeclaredConstructor(signature);
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }
    static Object field(Class<?> type, Object receiver, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(receiver);
    }
    static void reject(Class<?> plan, Class<?> sleep, Object raw, Object requested) throws Exception {
        try {
            make(plan, new Class<?>[]{sleep, sleep}, raw, requested);
            throw new AssertionError("expected refusal");
        } catch (InvocationTargetException ex) {
            check(ex.getCause().getClass().getSimpleName().equals("Rejected"));
        }
    }
    public static void main(String[] args) throws Exception {
        String base = "cn.local.micompanion.health.SleepWriteback";
        Class<?> writer = Class.forName(base), phase = Class.forName(base+"$Phase"),
                 sleep = Class.forName(base+"$NativeSleep"), plan = Class.forName(base+"$Plan");
        Class<?>[] phaseSig = {int.class, long.class, long.class, int.class};
        Class<?>[] sleepSig = {long.class, long.class, List.class, Map.class};
        // Intentionally non-chronological physical order. Keep state 6 and its physical slot.
        Object trace = make(phase, phaseSig, 0, 221L, 333L, 6);
        Object light = make(phase, phaseSig, 1, 101L, 221L, 3);
        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("duration", 2L);
        totals.put("sleep_light_duration", 2L);
        totals.put("sleep_deep_duration", 0L);
        Object raw = make(sleep, sleepSig, 101L, 333L, Arrays.asList(trace, light), totals);
        Method readTarget = writer.getDeclaredMethod("readTarget", JSONObject.class);
        Object requested = call(readTarget, null, new JSONObject("{start:101,end:333,stages:["
                +"{start:101,end:166,kind:'deep',source_index:1},{start:221,end:333,kind:'unknown',source_index:0}]}"));
        Object change = make(plan, new Class<?>[]{sleep, sleep}, raw, requested);
        Map<?,?> patched = (Map<?,?>) field(plan, change, "fields");
        check(patched.get("duration").equals(1L) && patched.get("sleep_light_duration").equals(0L)
                && patched.get("sleep_deep_duration").equals(1L));
        Object[] phases = (Object[]) field(plan, change, "next");
        check(field(phase, phases[0], "state").equals(6));
        check(field(phase, phases[1], "end").equals(166L));
        Method projection = sleep.getDeclaredMethod("projection");
        JSONObject snapshot = (JSONObject) call(projection, raw);
        check(snapshot.getJSONArray("stages").getJSONObject(0).getInt("source_index") == 1);
        check(snapshot.getLong("total_minutes") == 2L);
        totals.put("duration", 3L);
        reject(plan, sleep, raw, requested); // Do not guess an aggregate that deviates from the verified formula.
        totals.put("duration", 2L);
        totals.put("sleep_duration", 9000L);
        reject(plan, sleep, raw, requested);
        Object kindOnly = call(readTarget, null, new JSONObject("{start:101,end:333,stages:["
                +"{start:101,end:221,kind:'deep',source_index:1},{start:221,end:333,kind:'unknown',source_index:0}]}"));
        Object kindChange = make(plan, new Class<?>[]{sleep, sleep}, raw, kindOnly);
        check(((Map<?,?>) field(plan, kindChange, "fields")).get("sleep_duration").equals(9000L));
        Object omission = call(readTarget, null, new JSONObject("{start:101,end:333,stages:["
                +"{start:221,end:333,kind:'unknown',source_index:0}]}"));
        reject(plan, sleep, raw, omission);
        Object unknownNew = call(readTarget, null, new JSONObject("{start:101,end:333,stages:["
                +"{start:101,end:221,kind:'unknown',source_index:1},{start:221,end:333,kind:'unknown',source_index:0}]}"));
        reject(plan, sleep, raw, unknownNew);
        Object duplicateIndex = call(readTarget, null, new JSONObject("{start:101,end:333,stages:["
                +"{start:101,end:221,kind:'deep',source_index:0},{start:221,end:333,kind:'unknown',source_index:0}]}"));
        reject(plan, sleep, raw, duplicateIndex);
        try {
            call(readTarget, null, new JSONObject("{start:101,end:333,stages:["
                +"{start:101,end:222,kind:'deep'},{start:221,end:333,kind:'light'}]}"));
            throw new AssertionError("overlap accepted");
        } catch (InvocationTargetException ex) {
            check(ex.getCause().getClass().getSimpleName().equals("Rejected"));
        }
    }
}
'''


if __name__ == '__main__':
    unittest.main()
