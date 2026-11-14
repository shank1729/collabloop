import { createDocumentSync, mapTextOffset, mergeDocumentText } from "./documentSync";
import { webcrypto } from "crypto";

const document = { id: 1, content: "Hello world", version: 0 };
const snapshot = (content, version) => ({ id: 1, content, version });
let clients;
beforeEach(() => {
  jest.useFakeTimers(); clients = []; window.sessionStorage.clear();
  Object.defineProperty(window, "crypto", { configurable: true, value: webcrypto });
});
afterEach(() => { clients.forEach(client => client.cancel()); jest.useRealTimers(); });
function client(options = {}) {
  const onChange = jest.fn();
  const onStatus = jest.fn();
  const save = jest.fn(async (id, request) => snapshot(request.content, request.baseVersion + 1));
  const load = jest.fn(async () => document);
  const sync = createDocumentSync({ document, onChange, onStatus, save, load, ...options });
  clients.push(sync);
  return { sync, onChange, onStatus, save, load };
}

test("a delayed acknowledgement never erases characters typed after the request", async () => {
  let acknowledge;
  const save = jest.fn(() => new Promise(resolve => { acknowledge = resolve; }));
  const { sync, onChange } = client({ save });
  sync.edit("Hello world a");
  const saving = sync.flush();
  sync.edit("Hello world abc");
  sync.receive(snapshot("Hello world a", 1));
  acknowledge(snapshot("Hello world a", 1));
  await saving;
  expect(onChange).toHaveBeenLastCalledWith("Hello world abc", expect.objectContaining({ version: 1 }));
  const next = sync.flush();
  expect(save.mock.calls[1][1]).toMatchObject({ content: "Hello world abc", baseVersion: 1 });
  acknowledge(snapshot("Hello world abc", 2));
  await next;
  sync.receive(snapshot("Hello world a", 1));
  expect(onChange).toHaveBeenLastCalledWith("Hello world abc", expect.objectContaining({ version: 2 }));
});

test("remote text arriving before the debounce is merged with the pending draft", async () => {
  const { sync, save, onChange } = client();
  sync.edit("Hello world!");
  sync.receive(snapshot("Hi world", 1));
  expect(onChange).toHaveBeenLastCalledWith("Hi world!", expect.anything());
  await sync.flush();
  expect(save).toHaveBeenCalledWith(1, expect.objectContaining({ content: "Hi world!", baseVersion: 1 }));
});

test("conflicting owner and collaborator saves converge without losing either edit", async () => {
  let current = { ...document };
  const accepted = new Map();
  const save = jest.fn(async (id, request) => {
    await Promise.resolve();
    if (accepted.has(request.editId)) return accepted.get(request.editId);
    if (current.version !== request.baseVersion) throw Object.assign(new Error("Conflict"), { status: 409 });
    current = snapshot(request.content, current.version + 1);
    accepted.set(request.editId, current);
    return current;
  });
  const owner = client({ save, load: async () => current });
  const editor = client({ save, load: async () => current });
  owner.sync.edit("Hi world");
  editor.sync.edit("Hello beautiful world");
  await Promise.all([owner.sync.flush(), editor.sync.flush()]);
  await editor.sync.flush();
  owner.sync.receive(current);
  editor.sync.receive(current);
  expect(current.content).toBe("Hi beautiful world");
  expect(owner.onChange).toHaveBeenLastCalledWith(current.content, expect.anything());
  expect(editor.onChange).toHaveBeenLastCalledWith(current.content, expect.anything());
});

test("a lost HTTP response retries the same edit without duplicating accepted text", async () => {
  const accepted = snapshot("Hello world!", 1);
  const save = jest.fn().mockRejectedValueOnce(new Error("Connection reset"))
    .mockResolvedValueOnce(accepted)
    .mockImplementation(async (id, request) => snapshot(request.content, request.baseVersion + 1));
  const { sync, onChange } = client({ save });
  sync.edit(accepted.content);
  await sync.flush();
  sync.edit("Hello world!!");
  sync.receive(snapshot("Hi world!", 2));
  await sync.flush();
  expect(save.mock.calls[1][1].editId).toBe(save.mock.calls[0][1].editId);
  expect(onChange).toHaveBeenLastCalledWith("Hi world!!", expect.objectContaining({ version: 2 }));
  await sync.flush();
  expect(save.mock.calls[2][1]).toMatchObject({ content: "Hi world!!", baseVersion: 2 });
});

test("recovering a draft after reload reuses the outstanding edit ID", async () => {
  const storageKey = "draft:owner:1";
  const previous = client({ storage: window.sessionStorage, storageKey,
    save: async () => { throw new Error("Offline"); } });
  previous.sync.edit("Hello world!");
  await previous.sync.flush();
  const stored = JSON.parse(window.sessionStorage.getItem(storageKey));
  previous.sync.cancel();
  const restored = client({ document: snapshot("Hello world!", 1), storage: window.sessionStorage, storageKey });
  await restored.sync.flush();
  expect(restored.save).toHaveBeenCalledWith(1, expect.objectContaining({ editId: stored.request.editId }));
  expect(restored.onChange).toHaveBeenLastCalledWith("Hello world!", expect.anything());
  expect(window.sessionStorage.getItem(storageKey)).toBeNull();
});

test("a missed WebSocket update is recovered by a refresh while preserving local work", async () => {
  const { sync, onChange } = client({ load: async () => snapshot("Hi world", 2) });
  sync.edit("Hello world!");
  await sync.refresh();
  expect(onChange).toHaveBeenLastCalledWith("Hi world!", expect.anything());
});

test("access removal cancels pending saves", async () => {
  const { sync, save } = client();
  sync.edit("Private pending draft");
  sync.cancel();
  jest.advanceTimersByTime(10000);
  await sync.flush();
  expect(save).not.toHaveBeenCalled();
});

test("navigation flushes the old document without applying its response to the new view", async () => {
  const { sync, onChange, save } = client();
  sync.edit("Latest draft");
  sync.close();
  await Promise.resolve();
  expect(save).toHaveBeenCalledWith(1, expect.objectContaining({ content: "Latest draft" }));
  expect(onChange).toHaveBeenCalledTimes(1);
});

test.each([
  ["abcd", "ad", "abXcd", "aXd"],
  ["ab", "aLb", "aRb", "aRLb"],
  ["abc", "", "aNEWbc", "NEW"],
  ["😀 hello", "😀 hello!", "Hi 😀 hello", "Hi 😀 hello!"],
  ["one\ftwo", "one\ftwo!", "One\ftwo", "One\ftwo!"],
])("merges local edits without deleting remote insertions (%s)", (base, local, remote, expected) => {
  expect(mergeDocumentText(base, local, remote)).toBe(expected);
});

test("keeps the caret attached to text when remote changes arrive before it", () => {
  expect(mapTextOffset("hello", "Hi hello", 5)).toBe(8);
  expect(mapTextOffset("Hi hello", "hello", 8)).toBe(5);
  expect(mapTextOffset("😀hello", "x😀hello", 2)).toBe(3);
});

test("rapid interleaved typing survives reordered replies and a lost acknowledgement", async () => {
  let current = snapshot("", 0);
  const accepted = new Map();
  const views = ["", ""];
  const peers = [];
  let requestNumber = 0;
  const save = async (id, request) => {
    const number = ++requestNumber;
    await new Promise(resolve => setTimeout(resolve, number % 2 ? 80 : 25));
    if (accepted.has(request.editId)) return accepted.get(request.editId);
    if (request.baseVersion !== current.version) throw Object.assign(new Error("Conflict"), { status: 409 });
    current = snapshot(request.content, current.version + 1);
    accepted.set(request.editId, current);
    peers.forEach(peer => peer.receive(current));
    await new Promise(resolve => setTimeout(resolve, number % 3 ? 150 : 20));
    if (number === 1) throw new Error("Lost acknowledgement");
    return accepted.get(request.editId);
  };
  for (let index = 0; index < 2; index++) {
    const peer = client({ document: current, save, load: async () => current,
      onChange: text => { views[index] = text; } }).sync;
    peers.push(peer);
  }
  const expected = [];
  for (let index = 0; index < 40; index++) {
    const who = index % 2;
    const character = String.fromCharCode(0xe000 + index);
    expected.push(character);
    views[who] += character;
    peers[who].edit(views[who]);
    if (index % 5 === 0) peers[who].flush();
    jest.advanceTimersByTime(50);
    for (let tick = 0; tick < 8; tick++) await Promise.resolve();
  }
  for (let step = 0; step < 100; step++) {
    jest.advanceTimersByTime(100);
    for (let tick = 0; tick < 8; tick++) await Promise.resolve();
  }
  expect([...current.content].sort()).toEqual(expected.sort());
  expect(views).toEqual([current.content, current.content]);
});
