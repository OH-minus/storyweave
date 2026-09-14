"use strict";

const elements = {
    connectView: document.querySelector("#connectView"),
    gameView: document.querySelector("#gameView"),
    scoreView: document.querySelector("#scoreView"),
    connectForm: document.querySelector("#connectForm"),
    serverAddress: document.querySelector("#serverAddress"),
    playerName: document.querySelector("#playerName"),
    connectButton: document.querySelector("#connectButton"),
    connectStatus: document.querySelector("#connectStatus"),
    sidebar: document.querySelector("#playerSidebar"),
    sidebarToggle: document.querySelector("#sidebarToggle"),
    playerList: document.querySelector("#playerList"),
    instruction: document.querySelector("#instruction"),
    timerLabel: document.querySelector("#timerLabel"),
    headerTimer: document.querySelector("#headerTimer"),
    referencePanel: document.querySelector("#referencePanel"),
    referenceStory: document.querySelector("#referenceStory"),
    storyText: document.querySelector("#storyText"),
    turnBadge: document.querySelector("#turnBadge"),
    tokenInput: document.querySelector("#tokenInput"),
    submitButton: document.querySelector("#submitButton"),
    gameStatus: document.querySelector("#gameStatus"),
    scoreList: document.querySelector("#scoreList")
};

let apiBase = window.location.origin;
let playerId = "";
let connectionId = "";
let latestState = null;
let pollTimer = null;
const savedSessionKey = "storyweave.playerSession";

elements.serverAddress.value = window.location.origin.startsWith("http") ? window.location.origin : "http://localhost:8080";

elements.connectForm.addEventListener("submit", connect);
elements.submitButton.addEventListener("click", submitToken);
elements.tokenInput.addEventListener("input", updateComposer);
elements.tokenInput.addEventListener("keydown", event => {
    if (event.key === "Enter" && !elements.submitButton.disabled) {
        submitToken();
    }
});
elements.sidebarToggle.addEventListener("click", () => {
    const collapsed = elements.sidebar.classList.toggle("collapsed");
    elements.sidebarToggle.setAttribute("aria-expanded", String(!collapsed));
    elements.sidebarToggle.setAttribute("aria-label", collapsed ? "Expand player list" : "Collapse player list");
});
window.addEventListener("pagehide", notifyQuit);
restoreSession();

async function connect(event) {
    event.preventDefault();
    const name = elements.playerName.value;
    if (!/^[!-~]{1,24}$/.test(name)) {
        window.alert("Player names must use 1-24 visible ASCII characters and cannot contain spaces.");
        return;
    }
    try {
        apiBase = normalizeServerAddress(elements.serverAddress.value);
    } catch (error) {
        window.alert(error.message);
        return;
    }

    elements.connectButton.disabled = true;
    elements.connectStatus.textContent = "Finding the story room…";
    await joinGame(name, true);
}

async function joinGame(name, showAlert) {
    try {
        await request("/api/health");
        const joined = await request("/api/join", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({name})
        });
        playerId = joined.playerId;
        connectionId = joined.connectionId;
        saveSession(name);
        elements.referenceStory.textContent = joined.story;
        elements.connectView.classList.add("hidden");
        elements.gameView.classList.remove("hidden");
        await pollState();
        pollTimer = window.setInterval(pollState, 500);
    } catch (error) {
        elements.connectStatus.textContent = error.message;
        if (showAlert) {
            window.alert(`Could not connect to the server: ${error.message}`);
        }
        elements.connectButton.disabled = false;
    }
}

function saveSession(name) {
    try {
        window.localStorage.setItem(savedSessionKey, JSON.stringify({apiBase, name}));
    } catch (error) {
        // Rejoining by name still works when browser storage is unavailable.
    }
}

async function restoreSession() {
    let session;
    try {
        session = JSON.parse(window.localStorage.getItem(savedSessionKey));
    } catch (error) {
        return;
    }
    if (!session || typeof session.name !== "string" || typeof session.apiBase !== "string") {
        return;
    }
    try {
        apiBase = normalizeServerAddress(session.apiBase);
    } catch (error) {
        return;
    }
    elements.serverAddress.value = apiBase;
    elements.playerName.value = session.name;
    elements.connectButton.disabled = true;
    elements.connectStatus.textContent = "Rejoining the story room…";
    await joinGame(session.name, false);
}

function notifyQuit() {
    if (!playerId || !connectionId) {
        return;
    }
    const url = `${apiBase}/api/quit`;
    const body = new Blob([JSON.stringify({playerId, connectionId})], {type: "application/json"});
    if (!window.navigator.sendBeacon(url, body)) {
        fetch(url, {method: "POST", body, keepalive: true}).catch(() => {});
    }
}

function normalizeServerAddress(rawAddress) {
    let address = rawAddress.trim();
    if (!/^https?:\/\//i.test(address)) {
        address = `http://${address}`;
    }
    const parsed = new URL(address);
    if (!parsed.hostname || (parsed.protocol !== "http:" && parsed.protocol !== "https:")) {
        throw new Error("Enter a valid HTTP or HTTPS server address.");
    }
    return parsed.origin;
}

async function pollState() {
    try {
        latestState = await request(`/api/state?playerId=${encodeURIComponent(playerId)}`);
        renderState(latestState);
        elements.gameStatus.textContent = "";
    } catch (error) {
        elements.gameStatus.textContent = `Connection interrupted: ${error.message}`;
    }
}

function renderState(state) {
    if (state.phase === "FINISHED") {
        showScores(state.scores);
        return;
    }
    renderPlayers(state);
    renderStory(state.sharedStory);
    elements.referenceStory.textContent = state.story;
    const myTurn = state.phase === "PLAYING" && state.currentPlayerId === playerId;
    elements.tokenInput.disabled = !myTurn;
    elements.referencePanel.classList.toggle("hidden", state.phase === "WAITING");

    if (state.phase === "WAITING") {
        elements.instruction.textContent = `Waiting for ${state.expectedPlayers - state.players.length} more writer${state.expectedPlayers - state.players.length === 1 ? "" : "s"}`;
        elements.timerLabel.textContent = "Writers joined";
        elements.headerTimer.textContent = `${state.players.length}/${state.expectedPlayers}`;
        elements.turnBadge.textContent = "Lobby";
    } else if (state.phase === "READING") {
        elements.instruction.textContent = "Read your private story and remember its turning points";
        elements.timerLabel.textContent = "Reading time";
        elements.headerTimer.textContent = formatTime(state.phaseRemainingMillis);
        elements.turnBadge.textContent = "Read closely";
    } else if (state.phase === "SCORING") {
        elements.instruction.textContent = "The stories are being compared…";
        elements.timerLabel.textContent = "Scoring";
        elements.headerTimer.textContent = "•••";
        elements.turnBadge.textContent = "Finalizing";
    } else if (myTurn) {
        elements.instruction.textContent = "Your turn — add exactly one word or punctuation mark";
        elements.timerLabel.textContent = "Turn left";
        elements.headerTimer.textContent = formatTime(state.turnRemainingMillis);
        elements.turnBadge.textContent = "Your turn";
        window.setTimeout(() => elements.tokenInput.focus(), 0);
    } else {
        const current = state.players.find(player => player.id === state.currentPlayerId);
        elements.instruction.textContent = current ? `${current.name} is choosing the next token` : "Waiting for the next turn";
        elements.timerLabel.textContent = "Turn left";
        elements.headerTimer.textContent = formatTime(state.turnRemainingMillis);
        elements.turnBadge.textContent = "Read only";
    }
    updateComposer();
}

function renderPlayers(state) {
    elements.playerList.replaceChildren(...state.players.map(player => {
        const row = document.createElement("div");
        row.className = `player-row${player.id === state.currentPlayerId ? " current" : ""}${player.quit ? " quit" : ""}`;
        const avatar = document.createElement("span");
        avatar.className = "player-avatar";
        avatar.textContent = player.name.slice(0, 2).toUpperCase();
        const info = document.createElement("span");
        info.className = "player-info";
        const name = document.createElement("span");
        name.className = "player-name";
        name.textContent = player.name + (player.id === playerId ? " (you)" : "");
        const time = document.createElement("span");
        time.className = "player-time";
        time.textContent = player.quit
            ? "Quit"
            : player.active ? `${formatTime(player.remainingMillis)} remaining` : "Time expired";
        info.append(name, time);
        row.append(avatar, info);
        return row;
    }));
}

function renderStory(story) {
    if (!story) {
        const placeholder = document.createElement("span");
        placeholder.className = "empty-story";
        placeholder.textContent = "The first word is waiting to be written.";
        elements.storyText.replaceChildren(placeholder);
    } else {
        elements.storyText.textContent = story;
    }
}

function updateComposer() {
    const token = elements.tokenInput.value;
    const valid = validToken(token);
    const myTurn = latestState?.phase === "PLAYING" && latestState.currentPlayerId === playerId;
    elements.tokenInput.classList.toggle("invalid", token.length > 0 && !valid);
    elements.submitButton.disabled = !myTurn || !valid;
}

function validToken(token) {
    return /^[\p{L}\p{M}]+(?:['’-][\p{L}\p{M}]+)*$/u.test(token) || /^\p{P}$/u.test(token);
}

async function submitToken() {
    const token = elements.tokenInput.value;
    if (!validToken(token)) {
        return;
    }
    elements.submitButton.disabled = true;
    try {
        await request("/api/submit", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({playerId, token})
        });
        elements.tokenInput.value = "";
        await pollState();
    } catch (error) {
        elements.gameStatus.textContent = error.message;
        updateComposer();
    }
}

function showScores(scores) {
    if (pollTimer) {
        window.clearInterval(pollTimer);
    }
    elements.gameView.classList.add("hidden");
    elements.scoreView.classList.remove("hidden");
    elements.scoreList.replaceChildren(...scores.map(score => {
        const row = document.createElement("li");
        row.className = "score-entry";
        const rank = document.createElement("span");
        rank.className = "rank";
        rank.textContent = `#${score.rank}`;
        const name = document.createElement("strong");
        name.textContent = score.name + (score.playerId === playerId ? " (you)" : "");
        const value = document.createElement("span");
        value.className = "score-value";
        value.textContent = `${score.score} pts`;
        row.append(rank, name, value);
        return row;
    }));
}

function formatTime(milliseconds) {
    const totalSeconds = Math.max(0, Math.ceil(milliseconds / 1000));
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

async function request(path, options) {
    let response;
    try {
        response = await fetch(`${apiBase}${path}`, options);
    } catch (error) {
        throw new Error("Server not found or unreachable.");
    }
    let body;
    try {
        body = await response.json();
    } catch (error) {
        throw new Error(`Server returned an invalid response (HTTP ${response.status}).`);
    }
    if (!response.ok) {
        throw new Error(body.error || `Request failed with HTTP ${response.status}.`);
    }
    return body;
}