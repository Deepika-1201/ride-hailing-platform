import { useEffect, useState } from "react";
import { Driver } from "./screens/Driver";
import { Operations } from "./screens/Operations";
import { Rider } from "./screens/Rider";

const SCREENS = { ops: "Operations", rider: "Rider", driver: "Driver" } as const;
type Screen = keyof typeof SCREENS;

function current(): Screen {
  const name = window.location.hash.replace(/^#\/?/, "");
  return name in SCREENS ? (name as Screen) : "ops";
}

/** Three screens, one per tab, so a person can be the rider and the driver while watching the map (FR-S6). */
export function App() {
  const [screen, setScreen] = useState<Screen>(current);

  useEffect(() => {
    const onHash = () => setScreen(current());
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  return (
    <>
      <nav className="tabs" aria-label="Workspace">
        <strong className="brand">Ride Control</strong>
        {(Object.keys(SCREENS) as Screen[]).map((s) => (
          <a key={s} href={`#/${s}`} className={s === screen ? "active" : ""} aria-current={s === screen ? "page" : undefined}>
            {SCREENS[s]}
          </a>
        ))}
      </nav>
      {screen === "ops" && <Operations />}
      {screen === "rider" && <Rider />}
      {screen === "driver" && <Driver />}
    </>
  );
}
