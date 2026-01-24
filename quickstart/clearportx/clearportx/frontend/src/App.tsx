import { CSSProperties } from "react";
import { useWalletAuth } from "./wallet/useWalletAuth";

const containerStyle: CSSProperties = {
  minHeight: "100vh",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  background: "linear-gradient(135deg, #020617 0%, #0f172a 100%)",
  fontFamily: "'Inter', system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
};

const panelStyle: CSSProperties = {
  width: "100%",
  maxWidth: 420,
  padding: "2.5rem",
  borderRadius: "1.5rem",
  background: "rgba(15, 23, 42, 0.85)",
  boxShadow: "0 25px 50px -12px rgba(15, 23, 42, 0.65)",
  border: "1px solid rgba(148, 163, 184, 0.2)",
  color: "white",
};

const buttonStyle: CSSProperties = {
  width: "100%",
  padding: "0.85rem 1.5rem",
  borderRadius: "0.9rem",
  border: "none",
  background: "linear-gradient(90deg, #38bdf8, #6366f1)",
  color: "white",
  fontSize: "1rem",
  fontWeight: 600,
  cursor: "pointer",
  transition: "filter 0.2s ease",
};

const mutedTextStyle: CSSProperties = {
  color: "rgba(226, 232, 240, 0.75)",
  fontSize: "0.95rem",
  lineHeight: 1.5,
};

const labelStyle: CSSProperties = {
  fontSize: "0.9rem",
  letterSpacing: "0.06em",
  textTransform: "uppercase",
  color: "rgba(148, 163, 184, 0.9)",
};

export function App() {
  const {
    authenticateWithDev,
    partyId,
    walletType,
    loading,
    error,
  } = useWalletAuth();

  return (
    <div style={containerStyle}>
      <main style={panelStyle}>
        <p style={labelStyle}>Wallet Access</p>
        <h1 style={{ fontSize: "1.85rem", marginTop: "0.25rem", marginBottom: "0.75rem" }}>
          Connect to ClearportX
        </h1>
        <p style={mutedTextStyle}>
          Use the Dev Wallet connector to manually enter a Canton party ID and fetch a JWT via the
          existing challenge/verify API. This flow mirrors what Loop/Zoro wallets will perform in production.
        </p>

        <div style={{ marginTop: "1.75rem" }}>
          <button
            type="button"
            style={buttonStyle}
            disabled={loading}
            onClick={() => authenticateWithDev()}
          >
            {loading ? "Connecting…" : "Connect Dev Wallet"}
          </button>
        </div>

        <section style={{ marginTop: "1.5rem", padding: "1rem", borderRadius: "1rem", background: "rgba(30, 41, 59, 0.65)" }}>
          <p style={{ fontSize: "0.95rem", marginBottom: "0.35rem" }}>
            Status:{" "}
            <strong>
              {partyId ? `Connected as ${partyId}` : "Not connected"}
            </strong>
          </p>
          <p style={{ fontSize: "0.9rem", color: "rgba(148,163,184,0.9)" }}>
            Wallet type: {walletType ?? "n/a"}
          </p>
          {error && (
            <p style={{ marginTop: "0.5rem", color: "#fca5a5", fontSize: "0.9rem" }}>
              {error}
            </p>
          )}
        </section>
      </main>
    </div>
  );
}

export default App;


