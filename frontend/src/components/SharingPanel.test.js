import React, { useState } from "react";
import { act, fireEvent, render, screen } from "@testing-library/react";
import SharingPanel from "./SharingPanel";
import { removeCollaborator, shareDocument } from "../services/documentService";

jest.mock("../services/documentService", () => ({ removeCollaborator: jest.fn(), shareDocument: jest.fn() }));
const person = { id: 2, name: "Teammate", email: "team@example.com", role: "VIEWER" };
function Panel({ canManage = true }) {
  const [people, setPeople] = useState([person]);
  return <SharingPanel documentId={1} collaborators={people} canManage={canManage}
    onPendingChange={() => {}} onChange={(document) => setPeople(document.collaborators)} />;
}
beforeEach(() => jest.clearAllMocks());

test("invites with normalized email and viewer access by default", async () => {
  shareDocument.mockResolvedValue({ collaborators: [person] });
  render(<Panel />);
  fireEvent.change(screen.getByLabelText("Collaborator email"), { target: { value: "NEW@example.com" } });
  fireEvent.click(screen.getByRole("button", { name: "Share document" }));
  await screen.findByRole("status");
  expect(shareDocument).toHaveBeenCalledWith(1, "new@example.com", "VIEWER");
  expect(screen.getByLabelText("Collaborator email")).toHaveValue("");
});

test("changes a collaborator role after successful response", async () => {
  shareDocument.mockResolvedValue({ collaborators: [{ ...person, role: "EDITOR" }] });
  render(<Panel />);
  fireEvent.change(screen.getByLabelText("Role for team@example.com"), { target: { value: "EDITOR" } });
  expect(await screen.findByRole("status")).toHaveTextContent("now an editor");
  expect(shareDocument).toHaveBeenCalledWith(1, person.email, "EDITOR");
  expect(screen.getByLabelText("Role for team@example.com")).toHaveValue("EDITOR");
});

test("locks controls during role save and restores original role after failure", async () => {
  let rejectRequest;
  shareDocument.mockImplementationOnce(() => new Promise((resolve, reject) => { rejectRequest = reject; }));
  render(<Panel />);
  fireEvent.change(screen.getByLabelText("Role for team@example.com"), { target: { value: "EDITOR" } });
  expect(screen.getByRole("button", { name: "Share document" })).toBeDisabled();
  expect(screen.getByRole("button", { name: /remove access/i })).toBeDisabled();
  await act(async () => rejectRequest(new Error("Save failed")));
  expect(screen.getByRole("alert")).toHaveTextContent("Save failed");
  expect(screen.getByLabelText("Role for team@example.com")).toHaveValue("VIEWER");
  expect(screen.getByRole("button", { name: "Share document" })).toBeEnabled();
});

test("removal requires confirmation and then removes the collaborator row", async () => {
  removeCollaborator.mockResolvedValue({ collaborators: [] });
  const confirm = jest.spyOn(window, "confirm").mockReturnValueOnce(false).mockReturnValueOnce(true);
  try {
    render(<Panel />);
    fireEvent.click(screen.getByRole("button", { name: /remove access/i }));
    expect(removeCollaborator).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: /remove access/i }));
    expect(await screen.findByRole("status")).toHaveTextContent("Access removed");
    expect(removeCollaborator).toHaveBeenCalledWith(1, 2);
    expect(screen.queryByText(person.name)).not.toBeInTheDocument();
  } finally { confirm.mockRestore(); }
});

test("nonowners see names and roles without management controls", () => {
  render(<Panel canManage={false} />);
  expect(screen.getByText(person.name)).toBeInTheDocument();
  expect(screen.getByText("Viewer")).toBeInTheDocument();
  expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
  expect(screen.queryByRole("button")).not.toBeInTheDocument();
});
