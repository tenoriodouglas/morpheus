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

  // 1) Wake the CHILD when a manual override changed (immediate block/unblock).
  const childToken = after.childFcmToken;
  if (childToken && (before.manualSetAt || 0) !== (after.manualSetAt || 0)) {
    try {
      await getMessaging().send({
        token: childToken,
        data: {
          manualBlockUntil: String(after.manualBlockUntil ?? 0),
          manualUnblockUntil: String(after.manualUnblockUntil ?? 0),
          manualSetAt: String(after.manualSetAt ?? 0),
        },
        android: { priority: "high" },
      });
    } catch (e) {
      // A stale token throws; the child re-publishes a fresh one on next sync.
      console.error("child wake-up push failed:", e?.message || e);
    }
  }

  // 3) Ring the CALLEE on an incoming call (works with their app closed), and
  //    clear the ring once it's answered or cancelled. The callee is whoever the
  //    call did NOT come from.
  {
    const from = after.callFrom;
    const calleeToken = from === "parent" ? after.childFcmToken : after.parentFcmToken;
    const callerName = from === "parent" ? "Responsável" : (after.childName || "Filho");
    const wasRinging = (before.callStatus || "") === "ringing";
    const isRinging = (after.callStatus || "") === "ringing";
    const newRing = isRinging && (after.callAt || 0) > (before.callAt || 0);
    if (calleeToken && from) {
      try {
        if (newRing) {
          await getMessaging().send({
            token: calleeToken,
            data: {
              notifyType: "incoming_call",
              caller: String(callerName),
              video: String(Boolean(after.callVideo)),
            },
            android: { priority: "high" },
          });
        } else if (wasRinging && !isRinging) {
          // Answered / ended / declined -> stop the ring on the callee.
          await getMessaging().send({
            token: calleeToken,
            data: { notifyType: "call_cancelled" },
            android: { priority: "high" },
          });
        }
      } catch (e) {
        console.error("incoming-call push failed:", e?.message || e);
      }
    }
  }

  // 4) Tell the CHILD the parent's answer (approved/denied) to its extra-time
  //    request, so the feedback arrives even with the child app closed.
  {
    const childTok = after.childFcmToken;
    if (childTok && (after.reqRespAt || 0) > (before.reqRespAt || 0)) {
      try {
        await getMessaging().send({
          token: childTok,
          data: {
            notifyType: "req_response",
            approved: String(Boolean(after.reqRespApproved)),
          },
          android: { priority: "high" },
        });
      } catch (e) {
        console.error("req-response push failed:", e?.message || e);
      }
    }
  }

  // 2) Alert the PARENT when the child raises an SOS/alert or asks for more time,
  //    so the parent is notified even if its app is closed. childName is written
  //    by the parent onto the doc; fall back to a generic label.
  const parentToken = after.parentFcmToken;
  if (parentToken) {
    const childName = after.childName || "";
    const newAlert = (after.alertAt || 0) > (before.alertAt || 0) && after.alert;
    const newRequest = (after.reqAt || 0) > (before.reqAt || 0);
    if (newAlert || newRequest) {
      const isAlert = Boolean(newAlert);
      const text = isAlert
        ? (after.alert === "sos" ? "🆘 SOS — pedido de ajuda!" : "Evento de segurança no aparelho do filho")
        : (after.reqNote || "Pediu mais tempo");
      try {
        await getMessaging().send({
          token: parentToken,
          data: {
            notifyType: isAlert ? "alert" : "request",
            childName: String(childName),
            text: String(text),
          },
          android: { priority: "high" },
        });
      } catch (e) {
        console.error("parent alert push failed:", e?.message || e);
      }
    }
  }
});
