package cn.local.micompanion;

import cn.local.micompanion.health.BandFileImporter;
import java.security.MessageDigest;

/** The import wire boundary must reject corrupt/mislabelled files before native writes. */
public final class BandImportValidationTest {
 public static void main(String[] args) throws Exception {
  // Real 13-byte file with independently recorded wire ID and CRC32.
  byte[] raw=hex("0475f16a2002da00ffe37fe7f2");
  String sha=hex(MessageDigest.getInstance("SHA-256").digest(raw));
  BandFileImporter.validateFile("0475f16a2002da",sha,raw);
  reject("1475f16a2002da",sha,raw);
  reject("../0475f16a2002da",sha,raw);
  reject("0475f16a2002da",new String(new char[64]).replace('\0','0'),raw);
  byte[] corrupt=raw.clone();corrupt[8]^=1;
  reject("0475f16a2002da",hex(MessageDigest.getInstance("SHA-256").digest(corrupt)),corrupt);
  reject("0475f16a2002da",sha,new byte[180001]);
  System.out.println("BandImportValidationTest passed");
 }
 static void reject(String id,String sha,byte[] raw)throws Exception {
  try{BandFileImporter.validateFile(id,sha,raw);throw new AssertionError("accepted invalid file");}
  catch(IllegalArgumentException expected){}
 }
 static byte[] hex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
 static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x&255));return s.toString();}
}
