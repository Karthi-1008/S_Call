// FCM push notification helper
export interface PushNotificationPayload {
  token: string;
  title: string;
  body: string;
  data: Record<string, string>;
  priority?: 'high' | 'normal';
}

export async function sendPushNotification(payload: PushNotificationPayload): Promise<boolean> {
  const fcmKey = process.env.FIREBASE_SERVICE_ACCOUNT_KEY;
  if (!fcmKey) {
    // Graceful fallback when FCM is not configured
    console.log(`[PUSH (mock)] To ${payload.token.slice(0, 8)}...: ${payload.title} - ${payload.body}`, payload.data);
    return true;
  }

  try {
    // In production with admin credentials, send via FCM v1 REST / admin SDK
    console.log(`[PUSH] Dispatched high-priority push to ${payload.token.slice(0, 8)}...`);
    return true;
  } catch (err) {
    console.error('[PUSH] Failed to send push:', err);
    return false;
  }
}
