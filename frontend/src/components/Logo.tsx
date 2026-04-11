const Logo = ({ size = 32 }: { size?: number }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 48 48"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
  >
    {/* Outer ring */}
    <circle cx="24" cy="24" r="22" stroke="#6c5ce7" strokeWidth="2" />
    {/* Inner nodes */}
    <circle cx="24" cy="12" r="4" fill="#a29bfe" />
    <circle cx="14" cy="32" r="4" fill="#6c5ce7" />
    <circle cx="34" cy="32" r="4" fill="#6c5ce7" />
    <circle cx="24" cy="24" r="3" fill="#00cec9" />
    {/* Edges */}
    <line x1="24" y1="16" x2="24" y2="21" stroke="#a29bfe" strokeWidth="1.5" />
    <line x1="21" y1="25.5" x2="16.5" y2="29.5" stroke="#6c5ce7" strokeWidth="1.5" />
    <line x1="27" y1="25.5" x2="31.5" y2="29.5" stroke="#6c5ce7" strokeWidth="1.5" />
    <line x1="17" y1="33" x2="31" y2="33" stroke="#6c5ce7" strokeWidth="1" strokeDasharray="2 2" />
  </svg>
);

export default Logo;
