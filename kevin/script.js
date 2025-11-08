import { handleQuery } from "../backend/src/router.js";

const startBtn = document.getElementById('startBtn');
const stopBtn = document.getElementById('stopBtn');
const sendBtn = document.getElementById('sendBtn');
const transcriptElem = document.getElementById('transcript');
const replyElem = document.getElementById('reply');

let recognition;
let mediaRecorder;
let audioChunks = [];

startBtn.onclick = async () => {
    startBtn.disabled = true;
    stopBtn.disabled = false;

    // Request microphone access
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    
    // Setup MediaRecorder
    mediaRecorder = new MediaRecorder(stream);
    audioChunks = [];
    mediaRecorder.ondataavailable = e => audioChunks.push(e.data);
    mediaRecorder.start();

    // Setup SpeechRecognition
    recognition = new (window.SpeechRecognition || window.webkitSpeechRecognition)();
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.lang = 'en-US';

    recognition.onresult = (event) => {
        let transcript = '';
        for (let i = event.resultIndex; i < event.results.length; i++) {
            transcript += event.results[i][0].transcript;
        }
        transcriptElem.textContent = transcript;

        // Check if transcript starts with "hello"
        if (transcript.trim().toLowerCase().startsWith('hello')) {
            downloadAudio();
        }
    };

    recognition.start();
};

stopBtn.onclick = () => {
    recognition.stop();
    mediaRecorder.stop();
    startBtn.disabled = false;
    stopBtn.disabled = true;
};

// sendBtn.onclick = async () => {
//     transcriptElem.textContent = await handleQuery(transcriptElem.textContent, [40.748817, -73.985428]);
// }

// Function to download recorded audio
function downloadAudio() {
    mediaRecorder.stop();
    mediaRecorder.onstop = () => {
        const blob = new Blob(audioChunks, { type: 'audio/webm' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'speech.webm';
        a.click();
    };
}
