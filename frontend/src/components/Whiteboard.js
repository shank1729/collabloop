import React, { useEffect, useRef, useState } from "react";
import { useWebSocket } from "../context/WebSocketContext";
import {
  eraseWhiteboardStrokes, getWhiteboard, saveWhiteboardStroke, undoWhiteboardStroke,
} from "../services/documentService";
import "./Whiteboard.css";

const BOARD_WIDTH = 1000;
const BOARD_HEIGHT = 600;
const MAX_POINTS = 512;
const TOOLS = [
  ["PEN", "Pen", "M4 16l-1 5 5-1L20 8l-4-4z M14 6l4 4"],
  ["PENCIL", "Pencil", "M4 16l-1 5 5-1L21 7l-4-4z M14 6l4 4 M4 16l4 4"],
  ["LINE", "Line", "M4 20L20 4"],
  ["RECTANGLE", "Rectangle", "M4 6h16v12H4z"],
  ["ELLIPSE", "Ellipse", "M21 12a9 6 0 1 1-18 0 9 6 0 1 1 18 0z"],
  ["ARROW", "Arrow", "M4 20L20 4 M11 4h9v9"],
  ["ERASER", "Eraser", "M3 14l9-10 9 8-7 8H9z M7 10l10 9 M14 20h7"],
];
const COLORS = [
  ["Ink", "#202938"],
  ["Blue", "#2456d6"],
  ["Red", "#ba303f"],
  ["Green", "#097653"],
  ["Purple", "#8238ab"],
  ["Orange", "#b45417"],
  ["Pink", "#be3078"],
];

function boardPoint(event) {
  const bounds = event.currentTarget.getBoundingClientRect();
  const scale = Math.min(bounds.width / BOARD_WIDTH, bounds.height / BOARD_HEIGHT);
  const left = bounds.left + (bounds.width - BOARD_WIDTH * scale) / 2;
  const top = bounds.top + (bounds.height - BOARD_HEIGHT * scale) / 2;
  return {
    x: Math.round(Math.max(0, Math.min(BOARD_WIDTH, (event.clientX - left) / scale))),
    y: Math.round(Math.max(0, Math.min(BOARD_HEIGHT, (event.clientY - top) / scale))),
  };
}

function newStrokeId() {
  // getRandomValues also works when the app is served over HTTP on a local network.
  const bytes = window.crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join("-");
}

function isFreehand(tool) {
  return tool === "PEN" || tool === "PENCIL";
}

function addPoint(drawing, point) {
  if (!isFreehand(drawing.tool)) return { ...drawing, points: [drawing.points[0], point] };
  const last = drawing.points[drawing.points.length - 1];
  if (last.x === point.x && last.y === point.y) return drawing;
  // Keep long gestures within the request limit by thinning older samples.
  const points = drawing.points.length === MAX_POINTS
    ? drawing.points.filter((_, index) => index % 2 === 0)
    : drawing.points;
  return { ...drawing, points: [...points, point] };
}

function MarkShape({ tool, color, width, points }) {
  if (points.length === 1) {
    return <circle cx={points[0].x} cy={points[0].y} r={width / 2} fill={color} />;
  }
  const [start, end] = points;
  const style = { stroke: color, strokeWidth: width, strokeLinecap: "round", strokeLinejoin: "round", fill: "none" };
  const boxWidth = Math.abs(end.x - start.x);
  const boxHeight = Math.abs(end.y - start.y);
  if (tool === "RECTANGLE") {
    return <rect {...style} x={Math.min(start.x, end.x)} y={Math.min(start.y, end.y)} width={boxWidth} height={boxHeight} />;
  }
  if (tool === "ELLIPSE") {
    return <ellipse {...style} cx={(start.x + end.x) / 2} cy={(start.y + end.y) / 2}
      rx={boxWidth / 2} ry={boxHeight / 2} />;
  }
  if (tool === "ARROW") {
    const angle = Math.atan2(end.y - start.y, end.x - start.x);
    const tipLength = Math.min(Math.hypot(boxWidth, boxHeight) * 0.45, 18);
    const tip = (offset) => `${end.x - tipLength * Math.cos(angle + offset)} ${end.y - tipLength * Math.sin(angle + offset)}`;
    return <path {...style} d={`M${start.x} ${start.y} L${end.x} ${end.y} M${tip(-0.5)} L${end.x} ${end.y} L${tip(0.5)}`} />;
  }
  if (tool === "LINE") return <line {...style} x1={start.x} y1={start.y} x2={end.x} y2={end.y} />;
  return <polyline {...style} points={points.map(({ x, y }) => `${x},${y}`).join(" ")} />;
}

function Stroke({ drawing, muted = false, eraseTarget }) {
  const opacity = drawing.tool === "PENCIL" ? 0.65 : 1;
  return (
    <g>
      <g opacity={muted ? opacity * 0.3 : opacity} pointerEvents="none"><MarkShape {...drawing} /></g>
      {eraseTarget && (
        <g data-stroke-id={eraseTarget} pointerEvents={drawing.points.length === 1 ? "fill" : "stroke"}>
          <MarkShape {...drawing} color="transparent" width={drawing.width + 14} />
        </g>
      )}
    </g>
  );
}

export default function Whiteboard({ documentId, userId, canEdit }) {
  const { connected, subscribeToWhiteboard } = useWebSocket();
  const [strokes, setStrokes] = useState(null);
  const [pending, setPending] = useState([]);
  const [draft, setDraft] = useState(null);
  const [tool, setTool] = useState("PEN");
  const [color, setColor] = useState(COLORS[0][1]);
  const [width, setWidth] = useState(4);
  const [erasingIds, setErasingIds] = useState([]);
  const [erasing, setErasing] = useState(false);
  const [eraseError, setEraseError] = useState("");
  const [syncError, setSyncError] = useState("");
  const [undoError, setUndoError] = useState("");
  const [undoing, setUndoing] = useState(false);
  const gesture = useRef(null);
  const refreshBoard = useRef(null);

  useEffect(() => {
    let active = true;
    let queued = false;
    let inFlight = null;

    async function load() {
      try {
        // A change received during a read requires another read after it finishes.
        while (active && queued) {
          queued = false;
          try {
            const saved = await getWhiteboard(documentId);
            if (active) {
              setStrokes(saved);
              setSyncError("");
            }
          } catch (error) {
            if (active) {
              if (error.status === 403 || error.status === 404) setStrokes(null);
              setSyncError(error.message || "Unable to load the whiteboard.");
            }
          }
        }
      } finally {
        inFlight = null;
      }
    }

    function refresh() {
      queued = true;
      if (!inFlight) inFlight = load();
      return inFlight;
    }

    refreshBoard.current = refresh;
    refresh();
    // Recover a missed notification and keep the board usable without a socket.
    const poll = setInterval(() => {
      if (!document.hidden) refresh();
    }, 15000);
    window.addEventListener("focus", refresh);
    return () => {
      active = false;
      refreshBoard.current = null;
      clearInterval(poll);
      window.removeEventListener("focus", refresh);
    };
  }, [documentId]);

  useEffect(() => {
    if (!connected) return undefined;
    const unsubscribe = subscribeToWhiteboard(documentId, () => refreshBoard.current?.());
    refreshBoard.current?.();
    return unsubscribe;
  }, [connected, documentId, subscribeToWhiteboard]);

  useEffect(() => {
    if (!canEdit) {
      gesture.current = null;
      setDraft(null);
      setPending([]);
      setErasingIds([]);
    }
  }, [canEdit]);

  const canDraw = canEdit && strokes !== null && !erasing;
  const ownStroke = strokes?.slice().reverse().find((stroke) => stroke.authorId === userId);
  const savedIds = new Set(strokes?.map((stroke) => stroke.id));

  async function saveStroke(stroke) {
    const saving = { ...stroke, error: "" };
    setPending((current) => [...current.filter((item) => item.id !== stroke.id), saving]);
    try {
      await saveWhiteboardStroke(documentId, stroke.id, stroke.drawing);
      await refreshBoard.current?.();
      setPending((current) => current.filter((item) => item.id !== stroke.id));
    } catch (error) {
      setPending((current) => current.map((item) => item.id === stroke.id
        ? { ...item, error: error.message || "Unable to save the stroke." }
        : item));
      // The write may have committed even when the response was lost.
      refreshBoard.current?.();
    }
  }

  async function undoStroke() {
    setUndoing(true);
    setUndoError("");
    try {
      await undoWhiteboardStroke(documentId, ownStroke.id);
      await refreshBoard.current?.();
    } catch (error) {
      setUndoError(error.message || "Unable to undo the stroke.");
      refreshBoard.current?.();
    } finally {
      setUndoing(false);
    }
  }

  async function eraseStrokes(ids) {
    setErasing(true);
    setEraseError("");
    try {
      await eraseWhiteboardStrokes(documentId, ids);
      await refreshBoard.current?.();
    } catch (error) {
      setEraseError(error.message || "Unable to erase those marks.");
      refreshBoard.current?.();
    } finally {
      setErasing(false);
      setErasingIds([]);
    }
  }

  function pickEraseTarget(event, ids) {
    const hit = document.elementFromPoint(event.clientX, event.clientY)?.closest("[data-stroke-id]");
    if (!hit || !event.currentTarget.contains(hit) || ids.has(hit.dataset.strokeId)) return;
    ids.add(hit.dataset.strokeId);
    setErasingIds([...ids]);
  }

  function startStroke(event) {
    if (!canDraw || gesture.current || event.button !== 0) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    if (tool === "ERASER") {
      const ids = new Set();
      gesture.current = { pointerId: event.pointerId, ids };
      pickEraseTarget(event, ids);
      return;
    }
    const drawing = { tool, color, width: tool === "PENCIL" ? Math.max(1, Math.round(width / 2)) : width,
      points: [boardPoint(event)] };
    gesture.current = { pointerId: event.pointerId, drawing };
    setDraft(drawing);
  }

  function moveStroke(event) {
    if (gesture.current?.pointerId !== event.pointerId || !canDraw) return;
    if (gesture.current.ids) {
      pickEraseTarget(event, gesture.current.ids);
      return;
    }
    const drawing = addPoint(gesture.current.drawing, boardPoint(event));
    gesture.current.drawing = drawing;
    setDraft(drawing);
  }

  function cancelStroke(event) {
    if (gesture.current?.pointerId !== event.pointerId) return;
    gesture.current = null;
    setDraft(null);
    setErasingIds([]);
  }

  function finishStroke(event) {
    if (gesture.current?.pointerId !== event.pointerId) return;
    const { ids, drawing } = gesture.current;
    gesture.current = null;
    setDraft(null);
    if (!canDraw) return;
    if (ids) {
      if (ids.size) eraseStrokes([...ids]);
      return;
    }
    const finished = addPoint(drawing, boardPoint(event));
    if (!isFreehand(finished.tool)) {
      const [start, end] = finished.points;
      const flat = start.x === end.x || start.y === end.y;
      if ((start.x === end.x && start.y === end.y)
          || (flat && (finished.tool === "RECTANGLE" || finished.tool === "ELLIPSE"))) return;
    }
    saveStroke({ id: newStrokeId(), drawing: finished });
  }

  return (
    <section className="whiteboard" aria-label="Shared whiteboard">
      <p className="whiteboard-status">
        {canEdit
          ? tool === "ERASER" ? "Click or drag across a mark to erase it for everyone." : "Finished marks are shared with everyone on this board."
          : "You have view-only access."}
      </p>
      {canEdit && (
        <div className="whiteboard-toolbar">
          <div className="whiteboard-tools" role="group" aria-label="Drawing tools">
            {TOOLS.map(([value, label, icon]) => (
              <button key={value} type="button" className="whiteboard-tool" aria-pressed={tool === value}
                onClick={() => setTool(value)}>
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d={icon} /></svg>{label}
              </button>
            ))}
          </div>
          <div className="whiteboard-tools">
            <div className="whiteboard-colors" role="group" aria-label="Drawing colors">
              {COLORS.map(([name, value]) => (
                <button key={value} type="button" aria-label={name} title={name} aria-pressed={color === value}
                  style={{ "--swatch": value }} onClick={() => setColor(value)} />
              ))}
              <input type="color" aria-label="Custom color" title="Custom color" value={color}
                onChange={(event) => setColor(event.target.value)} />
            </div>
            <label>Size
              <select value={width} onChange={(event) => setWidth(Number(event.target.value))}>
                <option value={2}>Fine</option>
                <option value={4}>Medium</option>
                <option value={9}>Thick</option>
              </select>
            </label>
            <button type="button" className="dashboard-secondary whiteboard-undo" onClick={undoStroke} aria-busy={undoing}
              disabled={!ownStroke || pending.some((stroke) => !stroke.error) || undoing || erasing || erasingIds.length > 0}>
              {undoing ? "Undoing…" : "Undo my last stroke"}
            </button>
          </div>
        </div>
      )}
      {strokes === null && !syncError && <p role="status">Loading whiteboard…</p>}
      {syncError && <p role="alert">{syncError}{" "}
        <button type="button" className="dashboard-secondary" onClick={() => refreshBoard.current?.()}>Reload board</button>
      </p>}
      {undoError && <p role="alert">{undoError}</p>}
      {eraseError && <p role="alert">{eraseError}</p>}
      <div className="whiteboard-surface">
        <svg viewBox={`0 0 ${BOARD_WIDTH} ${BOARD_HEIGHT}`} role="img" aria-label="Whiteboard drawing"
          className={canDraw ? `whiteboard-drawable${tool === "ERASER" ? " whiteboard-eraser" : ""}` : undefined}
          onPointerDown={startStroke} onPointerMove={moveStroke} onPointerUp={finishStroke}
          onPointerCancel={cancelStroke} onLostPointerCapture={cancelStroke}>
          {strokes?.map((stroke) => (
            <Stroke key={stroke.id} drawing={stroke.drawing} muted={erasingIds.includes(stroke.id)}
              eraseTarget={canDraw && tool === "ERASER" ? stroke.id : undefined} />
          ))}
          {pending.filter((stroke) => !savedIds.has(stroke.id)).map((stroke) =>
            <Stroke key={stroke.id} drawing={stroke.drawing} muted={Boolean(stroke.error)} />)}
          {draft && <Stroke drawing={draft} />}
        </svg>
      </div>
      {canEdit && pending.filter((stroke) => stroke.error && !savedIds.has(stroke.id)).map((stroke) => (
        <div className="whiteboard-tools" key={stroke.id}>
          <span role="alert">{stroke.error}</span>
          <button type="button" className="dashboard-secondary" onClick={() => saveStroke(stroke)}>Retry stroke</button>
          <button type="button" className="dashboard-secondary"
            onClick={() => setPending((current) => current.filter((item) => item.id !== stroke.id))}>
            Dismiss
          </button>
        </div>
      ))}
    </section>
  );
}
