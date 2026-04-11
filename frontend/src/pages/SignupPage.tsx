import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { apiJson } from "../api";
import Logo from "../components/Logo";
import styles from "./LoginPage.module.css"; // Reuse login styles

export default function SignupPage() {
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [error, setError] = useState("");
  const [success, setSuccess] = useState(false);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      await apiJson("/api/auth/signup", {
        method: "POST",
        body: JSON.stringify({ username, email, password, display_name: displayName || undefined }),
      });
      setSuccess(true);
    } catch (err: any) {
      setError(err.message || "Registration failed");
    } finally {
      setLoading(false);
    }
  };

  if (success) {
    return (
      <div className={styles.page}>
        <div className={styles.card}>
          <div className={styles.header}>
            <Logo size={40} />
            <h1 className={styles.title}>Registration Submitted</h1>
            <p className={styles.subtitle}>
              Your account has been created but needs admin approval before you can sign in.
              Please contact your administrator.
            </p>
          </div>
          <p className={styles.footer}>
            <Link to="/login">Go to Sign in</Link>
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <div className={styles.header}>
          <Logo size={40} />
          <h1 className={styles.title}>Create Account</h1>
          <p className={styles.subtitle}>Sign up for OntoBoard</p>
        </div>

        <form onSubmit={handleSubmit} className={styles.form}>
          {error && <div className={styles.error}>{error}</div>}

          <label className={styles.label}>
            Username
            <input className={styles.input} type="text" value={username}
                   onChange={(e) => setUsername(e.target.value)} required autoFocus />
          </label>

          <label className={styles.label}>
            Email
            <input className={styles.input} type="email" value={email}
                   onChange={(e) => setEmail(e.target.value)} required />
          </label>

          <label className={styles.label}>
            Display Name (optional)
            <input className={styles.input} type="text" value={displayName}
                   onChange={(e) => setDisplayName(e.target.value)} />
          </label>

          <label className={styles.label}>
            Password
            <input className={styles.input} type="password" value={password}
                   onChange={(e) => setPassword(e.target.value)} required />
          </label>

          <button className={styles.btn} type="submit" disabled={loading}>
            {loading ? "Creating account..." : "Sign up"}
          </button>
        </form>

        <p className={styles.footer}>
          Already have an account? <Link to="/login">Sign in</Link>
        </p>
      </div>
    </div>
  );
}
