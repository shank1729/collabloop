import { render, screen } from "@testing-library/react";
import PresencePanel from "./PresencePanel";

test("shows initials, readable typing status, and distinct people count", () => {
  render(<PresencePanel connected users={[
    { userId: 1, name: "Alex Ray", status: "TYPING" },
    { userId: 2, name: "Sam Lee", status: "VIEWING" },
  ]} />);
  expect(screen.getByText("2 other people")).toBeInTheDocument();
  expect(screen.getByText("AR")).toBeInTheDocument();
  expect(screen.getByText("is typing...")).toBeInTheDocument();
  expect(screen.getByText("Viewing")).toBeInTheDocument();
});

test("shows unavailable presence instead of stale users while disconnected", () => {
  render(<PresencePanel connected={false} users={[{ userId: 1, name: "Alex" }]} />);
  expect(screen.getByRole("status")).toHaveTextContent("presence is unavailable");
  expect(screen.queryByText("Alex")).not.toBeInTheDocument();
});
