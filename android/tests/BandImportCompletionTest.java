package cn.local.micompanion;

import cn.local.micompanion.health.BandFileImporter.ImportState;

/** A settled native operation must not hold the replication queue in pending. */
public final class BandImportCompletionTest {
 public static void main(String[] args) {
  ImportState callbackFailure=new ImportState();
  callbackFailure.nativeStarted();
  callbackFailure.finishedWithFailure("callback failed");
  check(callbackFailure,"error","native_import_failed");
  ImportState ledgerFailure=new ImportState();
  ledgerFailure.nativeStarted();ledgerFailure.nativeSucceeded();
  ledgerFailure.finishedWithFailure("ledger_write_failed");
  check(ledgerFailure,"error","ledger_write_failed");
  ImportState accountChange=new ImportState();
  accountChange.nativeStarted();accountChange.nativeSucceeded();
  accountChange.finishedWithFailure("source_changed_after_import");
  check(accountChange,"error","source_scope_mismatch");
  ImportState liveTimeout=new ImportState();liveTimeout.nativeStarted();liveTimeout.waitingForNative();
  check(liveTimeout,"pending","native_inflight");
  liveTimeout.finishedWithFailure("callback failed after timeout");
  check(liveTimeout,"error","native_import_failed");
  ImportState unsupported=new ImportState();unsupported.nativeStarted();
  unsupported.finishedWithFailure("native_invalid_version");
  check(unsupported,"error","unsupported");
  if (!cn.local.micompanion.health.BandFileImporter.isUnsupportedResult(-2,"Invalid Version"))
   throw new AssertionError("invalid version must be skipped");
  if (!cn.local.micompanion.health.BandFileImporter.isUnsupportedResult(1,"not support dataId: test"))
   throw new AssertionError("native unsupported data type must be skipped");
  if (cn.local.micompanion.health.BandFileImporter.isUnsupportedResult(1,"database unavailable"))
   throw new AssertionError("ordinary native failure must remain retryable");
  ImportState notReady=new ImportState();notReady.finishedWithFailure("SDK unavailable");
  check(notReady,"error","health_not_ready");
  System.out.println("BandImportCompletionTest passed");
 }
 static void check(ImportState s,String status,String error) {
  if(!status.equals(s.status())||!error.equals(s.error()))throw new AssertionError(s.status()+"/"+s.error());
 }
}
