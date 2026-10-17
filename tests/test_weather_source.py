"""WeatherSource JVM integration against synthetic SQLite via a narrow Android test adapter.

The adapter only implements the platform methods used by WeatherSource. Real SQL and
read-only mode run in Python's sqlite3, not a mock SQL matcher. No private DB is opened.
"""
import json
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

CONTEXT = '''package android.content;
import java.io.File;
public class Context {
 private final File db;
 public Context(File db){this.db=db;}
 public Context createDeviceProtectedStorageContext(){return this;}
 public File getDatabasePath(String name){if(!name.equals("weather.db"))throw new AssertionError(name);return db;}
}'''
CURSOR = '''package android.database;
import org.json.*;
public final class Cursor implements AutoCloseable {
 private final JSONObject data;private int pos=-1;
 public Cursor(JSONObject data){this.data=data;}
 public boolean moveToFirst(){pos=0;return data.getJSONArray("rows").length()>0;}
 public boolean moveToNext(){return ++pos<data.getJSONArray("rows").length();}
 public int getColumnIndex(String name){JSONArray cols=data.getJSONArray("columns");for(int i=0;i<cols.length();i++)if(cols.getString(i).equals(name))return i;return -1;}
 private Object value(int col){return data.getJSONArray("rows").getJSONArray(pos).get(col);}
 public boolean isNull(int col){return JSONObject.NULL.equals(value(col));}
 public String getString(int col){return isNull(col)?null:value(col).toString();}
 public void close(){}
}'''
DATABASE = '''package android.database.sqlite;
import android.database.Cursor;
import java.io.*;
import java.util.*;
import org.json.*;
public final class SQLiteDatabase implements AutoCloseable {
 public static final int OPEN_READONLY=1,NO_LOCALIZED_COLLATORS=16;
 private final String path;
 private SQLiteDatabase(String path){this.path=path;}
 public static SQLiteDatabase openDatabase(String path,Object factory,int flags){
  if(flags!=(OPEN_READONLY|NO_LOCALIZED_COLLATORS))throw new AssertionError("must be read only");
  return new SQLiteDatabase(path);
 }
 public Cursor rawQuery(String sql,String[] args){try{
  ArrayList<String> command=new ArrayList<>(Arrays.asList("python3.12",System.getProperty("weather.bridge"),path,sql,new JSONArray(args==null?new String[0]:args).toString()));
  Process p=new ProcessBuilder(command).redirectErrorStream(true).start();
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
  while((n=p.getInputStream().read(b))!=-1)bytes.write(b,0,n);
  if(p.waitFor()!=0)throw new IllegalStateException("SQL unavailable");
  return new Cursor(new JSONObject(new String(bytes.toByteArray(),"UTF-8")));
 }catch(Exception e){throw new IllegalStateException("SQL unavailable",e);}}
 public void close(){}
}'''
RUNNER = '''package cn.local.micompanion;
import java.io.File;
import org.json.*;
public final class WeatherSourceIntegration {
 public static void main(String[] args)throws Exception {
  JSONObject base=new JSONObject().put("collected_at","2026-01-02T12:00:00+08:00");
  System.out.println(WeatherSource.collect(new android.content.Context(new File(args[0])),base));
 }
}'''
BRIDGE = '''import json,sqlite3,sys
from pathlib import Path
with sqlite3.connect(Path(sys.argv[1]).as_uri()+"?mode=ro",uri=True) as c:
 q=c.execute(sys.argv[2],json.loads(sys.argv[3]))
 print(json.dumps({"columns":[x[0] for x in q.description],"rows":q.fetchall()}))
'''


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
class WeatherSourceIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="weather-source-test-")
        cls.path = Path(cls.tmp.name)
        cls.bridge = cls.path / "bridge.py"
        cls.bridge.write_text(BRIDGE)
        files = []
        for name, content in (("android/content/Context.java", CONTEXT),
                              ("android/database/Cursor.java", CURSOR),
                              ("android/database/sqlite/SQLiteDatabase.java", DATABASE),
                              ("cn/local/micompanion/WeatherSourceIntegration.java", RUNNER)):
            path = cls.path / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            files.append(str(path))
        cls.classes = cls.path / "classes"
        cls.cp = str(ROOT / "tools/deps/json.jar")
        source = ROOT / "android/src/cn/local/micompanion"
        subprocess.run(["javac", "--release", "8", "-cp", cls.cp, "-d", str(cls.classes),
                        *files, str(source / "WeatherSource.java"), str(source / "WeatherProjection.java")],
                       check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def collect(self, setup):
        with tempfile.TemporaryDirectory(dir=self.path) as tmp:
            db = Path(tmp) / "weather.db"
            with sqlite3.connect(db) as c:
                setup(c)
            before = db.read_bytes()
            p = subprocess.run(["java", f"-Dweather.bridge={self.bridge}", "-cp",
                                f"{self.classes}:{self.cp}", "cn.local.micompanion.WeatherSourceIntegration", str(db)],
                               check=True, capture_output=True, text=True)
            self.assertEqual(before, db.read_bytes(), "collector must not modify SQLite")
            result = json.loads(p.stdout)
            self.assertEqual(set(result), {"status", "source_package", "collected_at", "source_updated_at", "fields", "reason"})
            self.assertNotIn("PRIVATE", p.stdout)
            return result

    @staticmethod
    def selected(c):
        c.execute("CREATE TABLE selectedcity(posID TEXT,position INTEGER,name TEXT,latitude TEXT)")
        c.execute("INSERT INTO selectedcity VALUES('PRIVATE',0,'PRIVATE','PRIVATE')")

    def test_missing_optional_columns_and_tables_preserve_current(self):
        def setup(c):
            self.selected(c)
            c.execute("CREATE TABLE raw(city_id TEXT,data1 TEXT)")
            raw = {"current": {"temperature": {"value": "20", "unit": "℃"},
                               "pubTime": "2026-01-02T12:00:00+08:00"}, "sid": "PRIVATE"}
            c.execute("INSERT INTO raw VALUES('PRIVATE',?)", (json.dumps(raw),))
        r = self.collect(setup)
        self.assertEqual(r["fields"]["temperature"]["value"], 20)
        self.assertNotEqual(r["status"], "error")

    def test_raw_sql_failure_does_not_suppress_aqi_and_alerts(self):
        def setup(c):
            self.selected(c)
            c.execute("CREATE TABLE aqiinfo(city_id TEXT,pm25 TEXT)")
            c.execute("INSERT INTO aqiinfo VALUES('PRIVATE','12')")
            c.execute("CREATE TABLE alertinfo(city_id TEXT,title TEXT,detail TEXT,pub_time TEXT)")
            c.execute("INSERT INTO alertinfo VALUES('PRIVATE','预警','注意防范','2026-01-02 11:00:00')")
        r = self.collect(setup)
        self.assertEqual(r["fields"]["air_quality"]["pm25"]["value"], 12)
        self.assertEqual(r["fields"]["alerts"][0]["detail"], "注意防范")

    def test_today_sql_row_is_one_not_yesterday_zero(self):
        def setup(c):
            self.selected(c)
            c.execute("CREATE TABLE weather(city_id TEXT,day INTEGER,description TEXT,temperature TEXT)")
            c.execute("INSERT INTO weather VALUES('PRIVATE',0,'昨天','17℃')")
            c.execute("INSERT INTO weather VALUES('PRIVATE',1,'当前','20℃')")
        r = self.collect(setup)
        self.assertEqual(r["fields"]["description"], "当前")
        self.assertEqual(r["fields"]["temperature"]["value"], 20)
        self.assertIsNone(r["source_updated_at"], "SQL cache time is not observation")

    def test_missing_day_column_preserves_nonforecast_description(self):
        def setup(c):
            self.selected(c)
            c.execute("CREATE TABLE weather(city_id TEXT,description TEXT,temperature TEXT)")
            c.execute("INSERT INTO weather VALUES('PRIVATE','缓存描述','20℃')")
        r = self.collect(setup)
        self.assertEqual(r["fields"]["description"], "缓存描述")
        self.assertIsNone(r["source_updated_at"])

    def test_information_cards_are_selected_city_only(self):
        def setup(c):
            self.selected(c)
            c.execute("CREATE TABLE information(city_id TEXT,insert_time TEXT,data1 TEXT)")
            def card(summary):
                return json.dumps({"cards": [{"list": [{"type": "6", "template": "13", "data": {
                    "wtrIndexType": "clothing", "wtrTitle": "穿衣指数", "wtrSummary": summary}}]}]})
            c.execute("INSERT INTO information VALUES('other','1767326400000',?)", (card("PRIVATE"),))
            c.execute("INSERT INTO information VALUES('PRIVATE','1767326400000',?)", (card("建议外套"),))
        r = self.collect(setup)
        clothing = [x for x in r["fields"]["life_indices"] if x["type"] == "clothing"]
        self.assertEqual(len(clothing), 1, "same-city UI clothing index must be projected")
        self.assertEqual(clothing[0]["summary"], "建议外套")

    def test_malformed_deep_raw_uses_sql_fallback(self):
        def setup(c):
            self.selected(c)
            c.execute("CREATE TABLE raw(city_id TEXT,data1 TEXT)")
            c.execute("INSERT INTO raw VALUES('PRIVATE',?)", ('{"a":' * 100 + '0' + '}' * 100,))
            c.execute("CREATE TABLE weather(city_id TEXT,day INTEGER,description TEXT,temperature TEXT)")
            c.execute("INSERT INTO weather VALUES('PRIVATE',1,'备用','21℃')")
        r = self.collect(setup)
        self.assertEqual(r["fields"]["temperature"]["value"], 21)


if __name__ == "__main__":
    unittest.main()
