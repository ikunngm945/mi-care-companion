"""Exercise production sleep selection SQL against synthetic, read-only SQLite."""
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PREVIOUS = 1767312000
CURRENT = PREVIOUS + 86400
NOW = CURRENT + 1800

RUNNER = r'''package cn.local.micompanion;
import java.io.*;
import java.util.*;
import org.json.*;
public final class SleepSqlIntegration {
 public static void main(String[] args)throws Exception {
  HealthSource.Reader db=(sql,bind)->{
   Process p=new ProcessBuilder("python3.12",args[0],args[1],sql,new JSONArray(bind).toString()).start();
   ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
   while((n=p.getInputStream().read(b))!=-1)out.write(b,0,n);
   if(p.waitFor()!=0)throw new IOException("read-only fixture query failed");
   JSONObject data=new JSONObject(new String(out.toByteArray(),"UTF-8"));
   JSONArray rows=new JSONArray(),names=data.getJSONArray("columns"),values=data.getJSONArray("rows");
   for(int i=0;i<values.length();i++){
    JSONObject row=new JSONObject();
    for(int j=0;j<names.length();j++)row.put(names.getString(j),values.getJSONArray(i).get(j));
    rows.put(row);
   }
   return rows;
  };
  System.out.println(HealthCycle.collect(db,Long.parseLong(args[2]),TimeZone.getTimeZone("UTC")));
 }
}'''
BRIDGE = '''import json,sqlite3,sys
from pathlib import Path
with sqlite3.connect(Path(sys.argv[1]).as_uri()+"?mode=ro",uri=True) as db:
 db.execute("PRAGMA query_only=ON")
 q=db.execute(sys.argv[2],json.loads(sys.argv[3]))
 print(json.dumps({"columns":[v[0] for v in q.description],"rows":q.fetchall()}))
'''


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
class SleepSqlIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="mi-companion-sleep-sql-")
        cls.work = Path(cls.temp.name)
        cls.bridge = cls.work / "bridge.py"
        cls.bridge.write_text(BRIDGE)
        runner = cls.work / "SleepSqlIntegration.java"
        runner.write_text(RUNNER)
        cls.classes = cls.work / "classes"
        cls.classes.mkdir()
        deps = ROOT / "tools/deps"
        cls.cp = ":".join(map(str, [cls.classes, deps / "json.jar", deps / "android.jar", deps / "xposed-api.jar"]))
        subprocess.run(["javac", "--release", "8", "-encoding", "UTF-8", "-cp", cls.cp, "-d", str(cls.classes),
                        *map(str, sorted((ROOT / "android/src/cn/local/micompanion").glob("*.java"))),
                        str(runner)], check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    @staticmethod
    def sleep(end, duration, complete=1, deleted=0, sql_end=None):
        start = end - (duration + 15) * 60
        value = {"bedtime": start, "wake_up_time": end, "duration": duration,
                 "items": [{"start_time": start, "end_time": end - 60, "state": 3},
                           {"start_time": end - 60, "end_time": end, "state": 5}]}
        return (json.dumps(value), end, end if sql_end is None else sql_end, "synthetic", "sleep", complete, deleted)

    def collect(self, extra):
        with tempfile.TemporaryDirectory(dir=self.work) as d:
            path = Path(d) / "fixture.sqlite3"
            with sqlite3.connect(path) as db:
                db.execute("CREATE TABLE sleep_segment(value TEXT,time INTEGER,endTime INTEGER,sid TEXT,key TEXT,isComplete INTEGER,isDeleted INTEGER)")
                rows = [self.sleep(PREVIOUS - 86400, 370), self.sleep(PREVIOUS, 365), *extra]
                db.executemany("INSERT INTO sleep_segment VALUES(?,?,?,?,?,?,?)", rows)
            before = hashlib.sha256(path.read_bytes()).digest()
            p = subprocess.run(["java", "-cp", self.cp, "cn.local.micompanion.SleepSqlIntegration",
                                str(self.bridge), str(path), str(NOW * 1000)],
                               check=True, capture_output=True, text=True)
            self.assertEqual(hashlib.sha256(path.read_bytes()).digest(), before, "source DB changed")
            return json.loads(p.stdout)

    def test_synced_zero_flag_main_sleep_is_not_replaced_by_yesterdays(self):
        cycle = self.collect([self.sleep(CURRENT, 310, complete=0, sql_end=CURRENT + 60),
                              self.sleep(CURRENT + 90, 125)])
        self.assertEqual(cycle["status"], "ok")
        self.assertEqual(cycle["sleep"][0]["end_ms"], CURRENT * 1000,
                         "isComplete=0 must not hide an ended synced main sleep")
        self.assertEqual(cycle["start_ms"], PREVIOUS * 1000)
        self.assertEqual(cycle["end_ms"], NOW * 1000)
        self.assertEqual(len(cycle["sleep"][0]["stages"]), 2)

    def test_one_flag_main_sleep_still_selected(self):
        cycle = self.collect([self.sleep(CURRENT, 310)])
        self.assertEqual(cycle["sleep"][0]["end_ms"], CURRENT * 1000)
        self.assertEqual(cycle["start_ms"], PREVIOUS * 1000)

    def test_short_zero_flag_sleep_does_not_advance_cycle(self):
        cycle = self.collect([self.sleep(CURRENT, 125, complete=0)])
        self.assertEqual(cycle["sleep"][0]["end_ms"], PREVIOUS * 1000)

    def test_deleted_sleep_is_not_selected(self):
        cycle = self.collect([self.sleep(CURRENT, 310, complete=0, deleted=1)])
        self.assertEqual(cycle["sleep"][0]["end_ms"], PREVIOUS * 1000)

    def test_future_wakeup_is_not_a_finished_sleep(self):
        cycle = self.collect([self.sleep(NOW + 60, 310, complete=0)])
        self.assertEqual(cycle["sleep"][0]["end_ms"], PREVIOUS * 1000)

    def test_invalid_source_duration_still_fails_closed(self):
        row = list(self.sleep(CURRENT, 310, complete=0))
        value = json.loads(row[0]);value["duration"] = 99999;row[0] = json.dumps(value)
        self.assertEqual(self.collect([row])["status"], "error")


if __name__ == "__main__":
    unittest.main()
