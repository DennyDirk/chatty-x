import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { NavLink, Route, Routes } from "react-router-dom";
import {
  MessageCircle,
  Link2,
  SlidersHorizontal,
  FlaskConical,
  ChartNoAxesCombined,
  Moon,
  Sun,
  Pause,
  LogOut,
  ShieldCheck,
} from "lucide-react";
import { api, errorText } from "./lib/api";
import type { Settings } from "./lib/types";
import { Auth } from "./components/Auth";
import { Conversations } from "./components/Conversations";
import { Accounts, Profile, Playground, Overview } from "./components/Pages";
import { Button } from "./components/ui/button";
export default function App() {
  const cache = useQueryClient();
  const status = useQuery({
    queryKey: ["identity"],
    queryFn: () => api<{ setupRequired: boolean; authenticated: boolean }>("/identity/status"),
    refetchInterval: 60000,
  });
  const [dark, setDark] = useState(localStorage.getItem("theme") === "dark");
  const [error, setError] = useState("");
  const settings = useQuery({
    queryKey: ["data", "settings", "global"],
    queryFn: () => api<Settings>("/settings"),
    enabled: status.data?.authenticated === true,
  });
  useEffect(() => {
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    localStorage.setItem("theme", dark ? "dark" : "light");
  }, [dark]);
  useEffect(() => {
    if (!status.data?.authenticated) return;
    const stream = new EventSource("/api/v1/events");
    const refresh = () => void cache.invalidateQueries({ queryKey: ["data"] });
    stream.addEventListener("refresh", refresh);
    return () => stream.close();
  }, [status.data?.authenticated, cache]);
  if (status.isError)
    return (
      <main className="empty fullscreen">
        <h1>Нет связи с пространством</h1>
        <p>{errorText(status.error)}</p>
        <Button onClick={() => void status.refetch()}>Попробовать снова</Button>
      </main>
    );
  if (!status.data)
    return (
      <main className="empty fullscreen">
        <span className="brand-mark">
          <MessageCircle />
        </span>
        <p>Открываем ваше пространство…</p>
      </main>
    );
  if (!status.data.authenticated)
    return (
      <Auth
        setup={status.data.setupRequired}
        onDone={() => {
          cache.clear();
          void status.refetch();
        }}
      />
    );
  const links = [
    ["/", "Диалоги", MessageCircle],
    ["/accounts", "Аккаунты", Link2],
    ["/profile", "Характер", SlidersHorizontal],
    ["/playground", "Песочница", FlaskConical],
    ["/overview", "Обзор", ChartNoAxesCombined],
  ] as const;
  return (
    <div className="app">
      <aside className="rail">
        <NavLink to="/" className="brand" aria-label="Chatty-X">
          <span className="brand-mark">
            <MessageCircle size={23} />
          </span>
          <span className="brand-word">
            chatty<span className="brand-x">x</span>
          </span>
        </NavLink>
        <nav>
          {links.map(([to, label, Icon]) => (
            <NavLink
              key={to}
              to={to}
              end
              className={({ isActive }) => `nav-item ${isActive ? "active" : ""}`}
            >
              <Icon size={20} />
              <span>{label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="rail-bottom">
          <div className="private-label">
            <ShieldCheck size={16} />
            <span>Личное пространство</span>
          </div>
          <button className="nav-item" onClick={() => setDark((v) => !v)}>
            {dark ? <Sun size={19} /> : <Moon size={19} />}
            <span>{dark ? "Светлая тема" : "Тёмная тема"}</span>
          </button>
          <button
            className="nav-item"
            onClick={() =>
              void api("/identity/logout", "POST").then(() => {
                cache.clear();
                void status.refetch();
              })
            }
          >
            <LogOut size={19} />
            <span>Выйти</span>
          </button>
        </div>
      </aside>
      <main className="workspace">
        <header className="topbar">
          <div>
            <span className="topbar-title">Ваше пространство</span>
            <span className="topbar-subtitle">Внимание к каждому разговору</span>
          </div>
          <div className="topbar-controls">
            <span className={`automation-state ${settings.data?.body.enabled ? "running" : ""}`}>
              <span className="status-dot" />
              {settings.data?.body.enabled ? "Автоматизация включена" : "Автоответы на паузе"}
            </span>
            <Button
              variant="secondary"
              size="small"
              onClick={() =>
                void api("/stop", "POST")
                  .then(() => cache.invalidateQueries({ queryKey: ["data"] }))
                  .catch((e) => setError(errorText(e)))
              }
            >
              <Pause size={14} />
              <span>Остановить всё</span>
            </Button>
          </div>
        </header>
        {error && (
          <div className="error" role="alert">
            {error}
          </div>
        )}
        <Routes>
          <Route path="/" element={<Conversations />} />
          <Route path="/accounts" element={<Accounts />} />
          <Route path="/profile" element={<Profile />} />
          <Route path="/playground" element={<Playground />} />
          <Route path="/overview" element={<Overview />} />
        </Routes>
      </main>
    </div>
  );
}
