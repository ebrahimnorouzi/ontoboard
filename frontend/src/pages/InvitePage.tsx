import { useEffect, useState } from "react";
import { useParams, Link, useNavigate } from "react-router-dom";
import { useAuth } from "../auth";
import { useInviteAccept, InviteInfo } from "../hooks/useInvite";
import Logo from "../components/Logo";
import styles from "./InvitePage.module.css";

export default function InvitePage() {
  const { token } = useParams<{ token: string }>();
  const { user } = useAuth();
  const navigate = useNavigate();
  const { getInfo, accept, loading: accepting, error: acceptError } = useInviteAccept();

  const [info, setInfo] = useState<InviteInfo | null>(null);
  const [loadingInfo, setLoadingInfo] = useState(true);
  const [infoError, setInfoError] = useState<string | null>(null);
  const [accepted, setAccepted] = useState(false);
  const [acceptedBoardId, setAcceptedBoardId] = useState<string | null>(null);

  useEffect(() => {
    if (!token) return;
    setLoadingInfo(true);
    getInfo(token).then((data) => {
      if (data) {
        setInfo(data);
        if (!data.valid) {
          setInfoError("This invite link has expired or has been used up.");
        }
      } else {
        setInfoError("Invalid invite link.");
      }
      setLoadingInfo(false);
    });
  }, [token, getInfo]);

  const handleAccept = async () => {
    if (!token) return;
    const result = await accept(token);
    if (result) {
      setAccepted(true);
      setAcceptedBoardId(result.board_id);
    }
  };

  if (loadingInfo) {
    return (
      <div className={styles.page}>
        <div className={styles.card}>
          <div className={styles.spinner} />
        </div>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <div className={styles.header}>
          <Logo size={36} />
          <h1 className={styles.title}>Board Invitation</h1>
          {!infoError && info && (
            <p className={styles.subtitle}>
              You have been invited to collaborate on a board
            </p>
          )}
        </div>

        {infoError && (
          <>
            <div className={styles.error}>{infoError}</div>
            <Link to="/" className={styles.backLink}>
              &larr; Go to homepage
            </Link>
          </>
        )}

        {accepted && acceptedBoardId && (
          <>
            <div className={styles.success}>
              You have joined the board successfully.
            </div>
            <button
              className={styles.acceptBtn}
              onClick={() => navigate(`/board/${acceptedBoardId}`)}
            >
              Open Board
            </button>
          </>
        )}

        {info && !infoError && !accepted && (
          <>
            <div className={styles.info}>
              <div className={styles.infoRow}>
                <span className={styles.infoLabel}>Board</span>
                <span className={styles.infoValue}>{info.board_name}</span>
              </div>
              <div className={styles.infoRow}>
                <span className={styles.infoLabel}>Role</span>
                <span className={styles.roleBadge}>{info.role}</span>
              </div>
              <div className={styles.infoRow}>
                <span className={styles.infoLabel}>Invited by</span>
                <span className={styles.infoValue}>{info.created_by}</span>
              </div>
              {info.expires_at && (
                <div className={styles.infoRow}>
                  <span className={styles.infoLabel}>Expires</span>
                  <span className={styles.infoValue}>
                    {new Date(info.expires_at).toLocaleString()}
                  </span>
                </div>
              )}
            </div>

            {acceptError && <div className={styles.error}>{acceptError}</div>}

            {user ? (
              <button
                className={styles.acceptBtn}
                onClick={handleAccept}
                disabled={accepting}
              >
                {accepting ? "Joining..." : "Accept Invite"}
              </button>
            ) : (
              <>
                <p className={styles.subtitle}>
                  Sign in to accept this invitation.
                </p>
                <Link
                  to={`/login?redirect=/invite/${token}`}
                  className={styles.loginLink}
                >
                  Sign in
                </Link>
              </>
            )}
          </>
        )}

        <Link to="/" className={styles.backLink}>
          &larr; Back to home
        </Link>
      </div>
    </div>
  );
}
