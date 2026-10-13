// SeniorCall Family Web Client (PWA)

const API_BASE = window.location.origin.includes('localhost') || window.location.origin.includes('127.0.0.1')
  ? 'http://127.0.0.1:8080'
  : window.location.origin;

const WS_BASE = API_BASE.replace(/^http/, 'ws');

// State
let currentUser = JSON.parse(localStorage.getItem('seniorcall_family_user') || 'null');
let pairedElderId = localStorage.getItem('seniorcall_paired_elder') || null;
let ws = null;
let pc = null;
let localStream = null;
let screenStream = null;
let activeCallId = null;
let callStartTime = null;
let callTimerInterval = null;
let iceServers = [{ urls: 'stun:stun.l.google.com:19302' }];
let isMuted = false;
let isVideoDisabled = false;
let isSharingScreen = false;

// DOM Elements
const setupView = document.getElementById('setupView');
const pairingStep = document.getElementById('pairingStep');
const dashboardView = document.getElementById('dashboardView');
const callView = document.getElementById('callView');
const userBadge = document.getElementById('userBadge');
const userName = document.getElementById('userName');
const deviceNameInput = document.getElementById('deviceNameInput');
const pairCodeInput = document.getElementById('pairCodeInput');
const elderNameDisplay = document.getElementById('elderNameDisplay');
const elderStatusBadge = document.getElementById('elderStatusBadge');
const elderBatteryDisplay = document.getElementById('elderBatteryDisplay');
const remoteVideo = document.getElementById('remoteVideo');
const remoteScreenVideo = document.getElementById('remoteScreenVideo');
const localVideo = document.getElementById('localVideo');
const callTimer = document.getElementById('callTimer');
const screenShareBanner = document.getElementById('screenShareBanner');
const incomingCallModal = document.getElementById('incomingCallModal');
const contactsModal = document.getElementById('contactsModal');
const contactsList = document.getElementById('contactsList');

// Initialize
init();

async function init() {
  bindEvents();
  if (currentUser && currentUser.token) {
    showDashboard();
    connectWebSocket();
  } else {
    showSetup();
  }
}

function bindEvents() {
  document.getElementById('registerBtn').addEventListener('click', registerDevice);
  document.getElementById('pairBtn').addEventListener('click', confirmPairingCode);
  document.getElementById('logoutBtn').addEventListener('click', resetSession);
  document.getElementById('callVideoBtn').addEventListener('click', () => initiateCall('video'));
  document.getElementById('callAudioBtn').addEventListener('click', () => initiateCall('audio'));
  document.getElementById('endCallBtn').addEventListener('click', () => endCurrentCall('user_hung_up'));
  document.getElementById('toggleAudioBtn').addEventListener('click', toggleAudio);
  document.getElementById('toggleVideoBtn').addEventListener('click', toggleVideo);
  document.getElementById('shareScreenBtn').addEventListener('click', toggleScreenShare);
  document.getElementById('manageContactsBtn').addEventListener('click', openContactsManager);
  document.getElementById('closeContactsBtn').addEventListener('click', () => contactsModal.style.display = 'none');
  document.getElementById('addContactBtn').addEventListener('click', addContact);
  document.getElementById('acceptCallBtn').addEventListener('click', acceptIncomingCall);
  document.getElementById('declineCallBtn').addEventListener('click', declineIncomingCall);
}

function showSetup() {
  setupView.style.display = 'block';
  dashboardView.style.display = 'none';
  callView.style.display = 'none';
  userBadge.style.display = 'none';
}

function showDashboard() {
  setupView.style.display = 'none';
  dashboardView.style.display = 'block';
  callView.style.display = 'none';
  userBadge.style.display = 'flex';
  userName.textContent = currentUser.deviceName || 'Family';
}

function resetSession() {
  if (confirm('Reset pairing and sign out?')) {
    localStorage.clear();
    location.reload();
  }
}

// 1. Registration & Pairing
async function registerDevice() {
  const name = deviceNameInput.value.trim() || 'Family Member';
  try {
    const res = await fetch(`${API_BASE}/v1/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ role: 'family', deviceName: name }),
    });
    const data = await res.json();
    currentUser = {
      userId: data.userId,
      token: data.jwt,
      deviceName: name,
    };
    localStorage.setItem('seniorcall_family_user', JSON.stringify(currentUser));
    pairingStep.style.display = 'block';
    document.getElementById('registerBtn').style.display = 'none';
  } catch (err) {
    alert('Failed to register: ' + err.message);
  }
}

async function confirmPairingCode() {
  const code = pairCodeInput.value.trim();
  if (code.length !== 6) {
    alert('Please enter a 6-digit code');
    return;
  }

  try {
    const res = await fetch(`${API_BASE}/v1/pair/confirm`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${currentUser.token}`,
      },
      body: JSON.stringify({ code }),
    });
    const data = await res.json();
    if (data.success) {
      pairedElderId = data.elderId;
      localStorage.setItem('seniorcall_paired_elder', pairedElderId);
      showDashboard();
      connectWebSocket();
    } else {
      alert('Pairing failed: ' + (data.error || 'Invalid code'));
    }
  } catch (err) {
    alert('Pairing error: ' + err.message);
  }
}

// 2. WebSocket Signaling
function connectWebSocket() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;

  ws = new WebSocket(`${WS_BASE}/ws?token=${currentUser.token}`);

  ws.onopen = () => {
    console.log('[WS] Connected');
  };

  ws.onmessage = (event) => {
    try {
      const msg = JSON.parse(event.data);
      handleSignalingMessage(msg);
    } catch (err) {
      console.error('[WS] Error processing message:', err);
    }
  };

  ws.onclose = () => {
    console.warn('[WS] Closed, reconnecting in 3s...');
    setTimeout(connectWebSocket, 3000);
  };

  // Heartbeat ping every 15s
  setInterval(() => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ t: 'ping' }));
    }
  }, 15000);
}

function handleSignalingMessage(msg) {
  switch (msg.t) {
    case 'hello':
      if (msg.turn && msg.turn.length) {
        iceServers = msg.turn.map(t => ({
          urls: t.urls,
          username: t.username,
          credential: t.credential,
        }));
      }
      break;

    case 'presence':
      if (msg.userId === pairedElderId) {
        updateElderPresence(msg.online, msg.battery);
      }
      break;

    case 'call_invite':
      handleIncomingInvite(msg);
      break;

    case 'call_ringing':
      console.log('[Call] Elder phone is ringing...');
      break;

    case 'call_accept':
      handleCallAccepted(msg);
      break;

    case 'call_reject':
      alert(`Call was declined (${msg.reason || 'declined'})`);
      cleanupCall();
      break;

    case 'offer':
      handleRemoteOffer(msg);
      break;

    case 'answer':
      handleRemoteAnswer(msg);
      break;

    case 'ice':
      if (pc && msg.candidate) {
        pc.addIceCandidate(new RTCIceCandidate({
          candidate: msg.candidate,
          sdpMid: msg.sdpMid,
          sdpMLineIndex: msg.sdpMLineIndex,
        })).catch(console.error);
      }
      break;

    case 'screen_request':
      const acceptShare = confirm("Elder requested to share their screen. Allow?");
      ws.send(JSON.stringify({ t: 'screen_request_reply', callId: msg.callId, accepted: acceptShare }));
      break;

    case 'screen_request_reply':
      if (msg.accepted) {
        startScreenCapture();
      } else {
        alert('Elder declined screen sharing');
      }
      break;

    case 'screen_share_started':
      console.log('[Screen] Peer started sharing screen');
      break;

    case 'screen_share_stopped':
      remoteScreenVideo.style.display = 'none';
      remoteVideo.style.display = 'block';
      break;

    case 'call_end':
      alert('Call ended');
      cleanupCall();
      break;

    case 'error':
      alert(`Error: ${msg.message}`);
      cleanupCall();
      break;
  }
}

function updateElderPresence(online, battery) {
  if (online) {
    elderStatusBadge.textContent = 'Ready';
    elderStatusBadge.className = 'badge badge-online';
  } else {
    elderStatusBadge.textContent = 'Offline';
    elderStatusBadge.className = 'badge badge-offline';
  }
  if (battery !== undefined) {
    elderBatteryDisplay.textContent = `🔋 ${battery}%`;
  }
}

// 3. WebRTC Call Operations
async function initiateCall(mediaType) {
  if (!pairedElderId) {
    alert('Please pair with an elder device first');
    return;
  }

  try {
    localStream = await navigator.mediaDevices.getUserMedia({
      audio: true,
      video: mediaType === 'video',
    });
    localVideo.srcObject = localStream;

    setupPeerConnection();

    // Send call invite to elder
    ws.send(JSON.stringify({
      t: 'call_invite',
      to: pairedElderId,
      media: mediaType,
      autoAnswerAllowed: true,
    }));

    dashboardView.style.display = 'none';
    callView.style.display = 'flex';
  } catch (err) {
    alert('Failed to access camera/mic: ' + err.message);
  }
}

function setupPeerConnection() {
  pc = new RTCPeerConnection({ iceServers });

  if (localStream) {
    localStream.getTracks().forEach(track => pc.addTrack(track, localStream));
  }

  pc.onicecandidate = (event) => {
    if (event.candidate && activeCallId) {
      ws.send(JSON.stringify({
        t: 'ice',
        callId: activeCallId,
        candidate: event.candidate.candidate,
        sdpMid: event.candidate.sdpMid,
        sdpMLineIndex: event.candidate.sdpMLineIndex,
      }));
    }
  };

  pc.ontrack = (event) => {
    console.log('[RTC] Received remote track:', event.track.kind, event.track.id);
    if (event.track.id.startsWith('screen-')) {
      // Remote screen share track
      remoteScreenVideo.srcObject = event.streams[0];
      remoteScreenVideo.style.display = 'block';
      remoteVideo.style.display = 'none';
    } else {
      remoteVideo.srcObject = event.streams[0];
      remoteVideo.style.display = 'block';
    }
  };
}

async function handleCallAccepted(msg) {
  activeCallId = msg.callId;
  startCallTimer();

  const offer = await pc.createOffer();
  await pc.setLocalDescription(offer);

  ws.send(JSON.stringify({
    t: 'offer',
    callId: activeCallId,
    sdp: offer.sdp,
  }));
}

let pendingIncomingCall = null;
function handleIncomingInvite(msg) {
  pendingIncomingCall = msg;
  document.getElementById('incomingCallerName').textContent = `${msg.callerName || "Elder"} is calling`;
  document.getElementById('incomingCallType').textContent = `Incoming ${msg.media} call`;
  incomingCallModal.style.display = 'flex';
}

async function acceptIncomingCall() {
  if (!pendingIncomingCall) return;
  incomingCallModal.style.display = 'none';
  activeCallId = pendingIncomingCall.callId;

  try {
    localStream = await navigator.mediaDevices.getUserMedia({
      audio: true,
      video: pendingIncomingCall.media === 'video',
    });
    localVideo.srcObject = localStream;

    setupPeerConnection();

    ws.send(JSON.stringify({
      t: 'call_accept',
      callId: activeCallId,
    }));

    dashboardView.style.display = 'none';
    callView.style.display = 'flex';
    startCallTimer();
  } catch (err) {
    alert('Camera/mic permission denied');
    declineIncomingCall();
  }
}

function declineIncomingCall() {
  if (!pendingIncomingCall) return;
  incomingCallModal.style.display = 'none';
  ws.send(JSON.stringify({
    t: 'call_reject',
    callId: pendingIncomingCall.callId,
    reason: 'declined',
  }));
  pendingIncomingCall = null;
}

async function handleRemoteOffer(msg) {
  activeCallId = msg.callId;
  await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp: msg.sdp }));
  const answer = await pc.createAnswer();
  await pc.setLocalDescription(answer);

  ws.send(JSON.stringify({
    t: 'answer',
    callId: activeCallId,
    sdp: answer.sdp,
  }));
}

async function handleRemoteAnswer(msg) {
  await pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: msg.sdp }));
}

function toggleAudio() {
  if (!localStream) return;
  const audioTrack = localStream.getAudioTracks()[0];
  if (audioTrack) {
    isMuted = !isMuted;
    audioTrack.enabled = !isMuted;
    document.getElementById('toggleAudioBtn').querySelector('.label').textContent = isMuted ? 'Unmute' : 'Mute';
  }
}

function toggleVideo() {
  if (!localStream) return;
  const videoTrack = localStream.getVideoTracks()[0];
  if (videoTrack) {
    isVideoDisabled = !isVideoDisabled;
    videoTrack.enabled = !isVideoDisabled;
    document.getElementById('toggleVideoBtn').querySelector('.label').textContent = isVideoDisabled ? 'Start Cam' : 'Camera';
  }
}

async function toggleScreenShare() {
  if (isSharingScreen) {
    stopScreenCapture();
  } else {
    // Request permission or start
    try {
      await startScreenCapture();
    } catch (err) {
      console.error('Screen capture error:', err);
    }
  }
}

async function startScreenCapture() {
  screenStream = await navigator.mediaDevices.getDisplayMedia({ video: true });
  const screenTrack = screenStream.getVideoTracks()[0];

  const sender = pc.addTrack(screenTrack, screenStream);

  screenTrack.onended = () => {
    stopScreenCapture();
  };

  isSharingScreen = true;
  screenShareBanner.style.display = 'block';
  document.getElementById('shareScreenBtn').querySelector('.label').textContent = 'Stop Share';

  ws.send(JSON.stringify({
    t: 'screen_share_started',
    callId: activeCallId,
    trackId: screenTrack.id,
  }));
}

function stopScreenCapture() {
  if (screenStream) {
    screenStream.getTracks().forEach(t => t.stop());
    screenStream = null;
  }
  isSharingScreen = false;
  screenShareBanner.style.display = 'none';
  document.getElementById('shareScreenBtn').querySelector('.label').textContent = 'Share';

  if (activeCallId && ws) {
    ws.send(JSON.stringify({
      t: 'screen_share_stopped',
      callId: activeCallId,
    }));
  }
}

function endCurrentCall(reason = 'normal') {
  if (activeCallId && ws) {
    ws.send(JSON.stringify({
      t: 'call_end',
      callId: activeCallId,
      reason,
    }));
  }
  cleanupCall();
}

function cleanupCall() {
  if (callTimerInterval) clearInterval(callTimerInterval);
  if (screenStream) stopScreenCapture();
  if (localStream) {
    localStream.getTracks().forEach(t => t.stop());
    localStream = null;
  }
  if (pc) {
    pc.close();
    pc = null;
  }
  activeCallId = null;
  remoteVideo.srcObject = null;
  remoteScreenVideo.srcObject = null;
  localVideo.srcObject = null;

  showDashboard();
}

function startCallTimer() {
  callStartTime = Date.now();
  if (callTimerInterval) clearInterval(callTimerInterval);
  callTimerInterval = setInterval(() => {
    const elapsed = Math.floor((Date.now() - callStartTime) / 1000);
    const mins = String(Math.floor(elapsed / 60)).padStart(2, '0');
    const secs = String(elapsed % 60).padStart(2, '0');
    callTimer.textContent = `${mins}:${secs}`;
  }, 1000);
}

// 4. Remote Contacts Management
async function openContactsManager() {
  if (!pairedElderId) return;
  contactsModal.style.display = 'flex';
  await refreshContactsList();
}

async function refreshContactsList() {
  try {
    const res = await fetch(`${API_BASE}/v1/contacts?elderId=${pairedElderId}`, {
      headers: { Authorization: `Bearer ${currentUser.token}` },
    });
    const data = await res.json();
    renderContacts(data.contacts || []);
  } catch (err) {
    alert('Failed to load contacts: ' + err.message);
  }
}

function renderContacts(contacts) {
  contactsList.innerHTML = '';
  if (!contacts.length) {
    contactsList.innerHTML = '<p class="subtitle">No contacts yet. Add family members below.</p>';
    return;
  }
  contacts.forEach(c => {
    const div = document.createElement('div');
    div.className = 'contact-item';
    div.innerHTML = `
      <div>
        <strong>${c.name}</strong> ${c.autoAnswerAllowed ? '<span class="badge badge-online">Auto-Answer</span>' : ''}
      </div>
      <button class="btn-text" onclick="deleteContact('${c.id}')">Remove</button>
    `;
    contactsList.appendChild(div);
  });
}

async function addContact() {
  const name = document.getElementById('newContactName').value.trim();
  const autoAnswer = document.getElementById('newContactAutoAnswer').checked;
  if (!name) return;

  const res = await fetch(`${API_BASE}/v1/contacts?elderId=${pairedElderId}`, {
    headers: { Authorization: `Bearer ${currentUser.token}` },
  });
  const data = await res.json();
  const contacts = data.contacts || [];

  contacts.push({
    id: `ct_${Date.now()}`,
    name,
    role: 'family',
    autoAnswerAllowed: autoAnswer,
    priority: contacts.length + 1,
  });

  await fetch(`${API_BASE}/v1/contacts`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${currentUser.token}`,
    },
    body: JSON.stringify({ elderId: pairedElderId, contacts }),
  });

  document.getElementById('newContactName').value = '';
  document.getElementById('newContactAutoAnswer').checked = false;
  await refreshContactsList();
}

window.deleteContact = async function(id) {
  const res = await fetch(`${API_BASE}/v1/contacts?elderId=${pairedElderId}`, {
    headers: { Authorization: `Bearer ${currentUser.token}` },
  });
  const data = await res.json();
  const contacts = (data.contacts || []).filter(c => c.id !== id);

  await fetch(`${API_BASE}/v1/contacts`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${currentUser.token}`,
    },
    body: JSON.stringify({ elderId: pairedElderId, contacts }),
  });

  await refreshContactsList();
};
