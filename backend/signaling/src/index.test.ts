import test from 'node:test';
import assert from 'node:assert';
import http from 'node:http';
import { WebSocket } from 'ws';
import { createSignalingServer } from './index.js';

process.env.NODE_ENV = 'test';
process.env.JWT_SECRET = 'test-secret-key-at-least-32-characters-long';

test('SeniorCall Signaling Integration Test', async (t) => {
  const { server, wss } = createSignalingServer();

  await new Promise<void>((resolve) => {
    server.listen(0, () => resolve());
  });

  const port = (server.address() as any).port;
  const baseUrl = `http://127.0.0.1:${port}`;
  const wsUrl = `ws://127.0.0.1:${port}/ws`;

  t.after(() => {
    wss.close();
    if (typeof server.closeAllConnections === 'function') {
      server.closeAllConnections();
    }
    server.close();
  });

  // 1. Health check
  await t.test('Healthz endpoint returns 200', async () => {
    const res = await fetch(`${baseUrl}/healthz`);
    assert.strictEqual(res.status, 200);
    const data = await res.json() as any;
    assert.strictEqual(data.status, 'ok');
  });

  let elderToken = '';
  let elderId = '';
  let familyToken = '';
  let familyId = '';

  // 2. Register users
  await t.test('Register elder and family users', async () => {
    const elderRes = await fetch(`${baseUrl}/v1/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ role: 'senior', deviceName: "Dad's Phone" }),
    });
    assert.strictEqual(elderRes.status, 200);
    const elderData = await elderRes.json() as any;
    elderToken = elderData.jwt;
    elderId = elderData.userId;
    assert.ok(elderId.startsWith('usr_'));

    const familyRes = await fetch(`${baseUrl}/v1/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ role: 'family', deviceName: "Rahul's Pixel" }),
    });
    assert.strictEqual(familyRes.status, 200);
    const familyData = await familyRes.json() as any;
    familyToken = familyData.jwt;
    familyId = familyData.userId;
    assert.ok(familyId.startsWith('usr_'));
  });

  // 3. Pairing
  await t.test('Pair elder and family', async () => {
    const pairStartRes = await fetch(`${baseUrl}/v1/pair/start`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${elderToken}` },
    });
    assert.strictEqual(pairStartRes.status, 200);
    const { code } = await pairStartRes.json() as any;
    assert.ok(code && code.length === 6);

    const pairConfirmRes = await fetch(`${baseUrl}/v1/pair/confirm`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${familyToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ code }),
    });
    assert.strictEqual(pairConfirmRes.status, 200);
    const confirmData = await pairConfirmRes.json() as any;
    assert.strictEqual(confirmData.success, true);
    assert.strictEqual(confirmData.elderId, elderId);
  });

  // 4. WebSocket calling & media flow
  await t.test('End-to-end WebSocket call and screen share negotiation', async () => {
    const elderWs = new WebSocket(`${wsUrl}?token=${elderToken}`);
    const familyWs = new WebSocket(`${wsUrl}?token=${familyToken}`);

    const elderMessages: any[] = [];
    const familyMessages: any[] = [];

    elderWs.on('message', data => elderMessages.push(JSON.parse(data.toString())));
    familyWs.on('message', data => familyMessages.push(JSON.parse(data.toString())));

    await Promise.all([
      new Promise(res => elderWs.on('open', res)),
      new Promise(res => familyWs.on('open', res)),
    ]);

    // Wait for 'hello' on both
    await new Promise(r => setTimeout(r, 200));

    assert.ok(elderMessages.some(m => m.t === 'hello' && m.userId === elderId));
    assert.ok(familyMessages.some(m => m.t === 'hello' && m.userId === familyId));

    // Family places call to elder
    familyWs.send(JSON.stringify({
      t: 'call_invite',
      to: elderId,
      media: 'video',
      autoAnswerAllowed: true,
    }));

    // Wait for elder to receive invite
    await new Promise(r => setTimeout(r, 200));

    const inviteMsg = elderMessages.find(m => m.t === 'call_invite');
    assert.ok(inviteMsg, 'Elder should receive call_invite');
    const callId = inviteMsg.callId;
    assert.ok(callId);

    // Elder accepts call
    elderWs.send(JSON.stringify({
      t: 'call_accept',
      callId,
    }));

    await new Promise(r => setTimeout(r, 100));
    assert.ok(familyMessages.some(m => m.t === 'call_accept' && m.callId === callId));

    // Exchange offer / answer
    familyWs.send(JSON.stringify({
      t: 'offer',
      callId,
      sdp: 'v=0\r\no=family 123 456 IN IP4 127.0.0.1...',
    }));

    await new Promise(r => setTimeout(r, 100));
    const offerMsg = elderMessages.find(m => m.t === 'offer');
    assert.ok(offerMsg && offerMsg.sdp.includes('family'));

    elderWs.send(JSON.stringify({
      t: 'answer',
      callId,
      sdp: 'v=0\r\no=elder 789 012 IN IP4 127.0.0.1...',
    }));

    await new Promise(r => setTimeout(r, 100));
    const answerMsg = familyMessages.find(m => m.t === 'answer');
    assert.ok(answerMsg && answerMsg.sdp.includes('elder'));

    // Exchange ICE candidate
    familyWs.send(JSON.stringify({
      t: 'ice',
      callId,
      candidate: 'candidate:1 1 UDP 2122260223 192.168.1.100 50000 typ host',
      sdpMid: '0',
      sdpMLineIndex: 0,
    }));

    await new Promise(r => setTimeout(r, 100));
    const iceMsg = elderMessages.find(m => m.t === 'ice');
    assert.ok(iceMsg && iceMsg.candidate.includes('192.168.1.100'));

    // Screen sharing request from Family to Elder
    familyWs.send(JSON.stringify({
      t: 'screen_request',
      callId,
      from: familyId,
    }));

    await new Promise(r => setTimeout(r, 100));
    assert.ok(elderMessages.some(m => m.t === 'screen_request'));

    // Elder accepts screen share
    elderWs.send(JSON.stringify({
      t: 'screen_request_reply',
      callId,
      accepted: true,
    }));

    await new Promise(r => setTimeout(r, 100));
    assert.ok(familyMessages.some(m => m.t === 'screen_request_reply' && m.accepted === true));

    // Elder announces screen share started
    elderWs.send(JSON.stringify({
      t: 'screen_share_started',
      callId,
      trackId: 'screen-track-1',
    }));

    await new Promise(r => setTimeout(r, 100));
    assert.ok(familyMessages.some(m => m.t === 'screen_share_started' && m.trackId === 'screen-track-1'));

    // End call
    familyWs.send(JSON.stringify({
      t: 'call_end',
      callId,
      reason: 'user_hung_up',
    }));

    await new Promise(r => setTimeout(r, 100));
    assert.ok(elderMessages.some(m => m.t === 'call_end' && m.callId === callId));

    elderWs.close();
    familyWs.close();
  });
});
