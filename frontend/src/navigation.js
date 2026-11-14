import { useCallback, useLayoutEffect, useState } from "react";

export function useAppNavigation() {
  const [search, setSearch] = useState(() => window.location.search);
  useLayoutEffect(() => {
    const syncLocation = () => setSearch(window.location.search);
    window.addEventListener("popstate", syncLocation);
    return () => window.removeEventListener("popstate", syncLocation);
  }, []);

  const navigate = useCallback((screen, { documentId, whiteboard, replace = false } = {}) => {
    const url = new URL(window.location.href);
    ["page", "document", "tab"].forEach((key) => url.searchParams.delete(key));
    if (screen !== "landing") url.searchParams.set("page", screen);
    if (screen === "workspace" && documentId != null) {
      url.searchParams.set("document", documentId);
      if (whiteboard) url.searchParams.set("tab", "whiteboard");
    }
    url.hash = "";
    if (url.href !== window.location.href) {
      if (replace) window.history.replaceState(null, "", url);
      else window.history.pushState(null, "", url);
    }
    setSearch(url.search);
  }, []);

  const params = new URLSearchParams(search);
  const page = params.get("page");
  const screen = ["login", "register", "workspace"].includes(page) ? page : "landing";
  const id = Number(params.get("document"));
  const documentId = screen === "workspace" && Number.isSafeInteger(id) && id > 0 ? id : null;
  return { screen, documentId, whiteboard: documentId != null && params.get("tab") === "whiteboard", navigate };
}
