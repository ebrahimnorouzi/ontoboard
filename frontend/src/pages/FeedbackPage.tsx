/**
 * FeedbackPage — standalone page that opens the feedback dialog.
 * Used when notification links point to /feedback.
 */
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import Navbar from "../components/Navbar";
import FeedbackDialog from "../components/FeedbackDialog";

export default function FeedbackPage() {
  const [open, setOpen] = useState(true);
  const navigate = useNavigate();

  useEffect(() => {
    if (!open) navigate(-1);
  }, [open, navigate]);

  return (
    <>
      <Navbar />
      {open && <FeedbackDialog onClose={() => setOpen(false)} />}
    </>
  );
}
