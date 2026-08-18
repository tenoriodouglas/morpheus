// Optional Morpheus wake-up push.
//
// When the parent changes a manual override on a family doc (immediate
// block/unblock), this sends a HIGH-PRIORITY FCM data message to the child's
// device so it re-applies enforcement right away — even if the child app was
// swiped away or the phone is in Doze. Without this function, the app still
// works whenever the child's process is alive (Firestore realtime listener);
// this just makes "immediate" reliable in the killed/dozing case.
//
// Deploy (requires the Firebase Blaze plan):
//   cd functions && npm install && cd ..
//   firebase deploy --only functions,firestore:rules
const { onDocumentWritten } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

exports.onFamilyChange = onDocumentWritten("families/{childId}", async (event) => {
  const before = event.data?.before?.data() || {};
  const after = event.data?.after?.data() || {};

  const token = after.childFcmToken;
  if (!token) return;

  // Only wake the child when a manual override actually changed.
  if ((before.manualSetAt || 0) === (after.manualSetAt || 0)) return;

  try {
    await getMessaging().send({
      token,
      data: {
        manualBlockUntil: String(after.manualBlockUntil ?? 0),
        manualUnblockUntil: String(after.manualUnblockUntil ?? 0),
        manualSetAt: String(after.manualSetAt ?? 0),
      },
      android: { priority: "high" },
    });
  } catch (e) {
    // A stale token throws; the child re-publishes a fresh one on next sync.
    console.error("wake-up push failed:", e?.message || e);
  }
});
