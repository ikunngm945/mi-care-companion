package cn.local.micompanion;
public final class SnapshotStoreBudgetTest {
 private static int checks;
 private static String repeat(String value,int count){StringBuilder out=new StringBuilder();for(int i=0;i<count;i++)out.append(value);return out.toString();}
 private static void accepted(String raw){SnapshotStore.validateSize(raw);checks++;}
 private static void rejected(String raw){
  try{SnapshotStore.validateSize(raw);throw new AssertionError("null or UTF-8 payload over 256KiB must be rejected");}catch(IllegalArgumentException expected){checks++;}
 }
 public static void main(String[] args){
  accepted(repeat("a",65537));
  accepted(repeat("a",262144));
  accepted(repeat("中",87381)+"a");
  accepted(repeat("😀",65536));
  rejected(repeat("a",262145));
  rejected(repeat("中",87382));
  rejected(repeat("😀",65536)+"a");
  rejected(null);
  System.out.println("SnapshotStoreBudget: "+checks+" checks passed");
 }
}
