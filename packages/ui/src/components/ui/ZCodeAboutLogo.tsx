import { cn } from "@/components/lib/utils.js";

export function ZCodeAboutLogo({ className }: { className?: string }) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width="100"
      height="100"
      fill="none"
      viewBox="0 0 256 256"
      className={cn("shrink-0 text-current", className)}
      aria-hidden="true"
      focusable="false"
    >
      <path fill="currentColor" fillRule="evenodd" clipRule="evenodd" d="M35.8 39.9L41.2 47.8L42.0 47.8L43.9 51.5L97.5 127.4L111.3 110.2L111.7 110.6L85.3 169.2L85.6 170.4L84.1 171.1L69.9 191.0L69.2 191.2L80.0 165.7L80.4 163.4L81.3 162.5L86.6 149.9L86.2 149.0L87.0 149.0L91.8 136.6L30.9 215.9L87.7 215.9L115.4 179.2L116.0 177.3L117.1 176.9L127.8 162.7L168.1 215.9L224.7 215.9L158.5 129.1L146.4 144.9L144.1 146.9L163.4 99.1L148.8 115.4L149.2 116.0L132.1 128.7L161.5 91.1L161.5 90.0L162.7 89.6L168.9 81.5L168.5 81.1L169.4 81.0L169.2 80.2L170.9 79.1L171.3 77.8L172.4 77.2L185.0 61.3L176.9 83.2L176.7 85.1L175.8 86.2L165.9 113.4L166.4 113.2L166.6 114.5L165.3 114.3L164.9 115.8L165.9 116.8L181.8 94.5L182.2 92.6L183.3 92.4L183.7 91.1L184.6 90.5L199.2 69.7L199.4 68.6L200.0 68.6L199.6 68.0L200.3 67.1L200.9 67.3L200.3 66.9L201.8 66.0L207.6 57.7L207.3 57.5L213.3 49.9L214.2 47.4L215.9 46.1L220.2 39.9L167.2 39.9L128.2 93.5L89.8 39.9Z" />
    </svg>
  );
}

export function ZCodeWordmarkLogo({ className }: { className?: string }) {
  return (
    <svg
      width="244"
      height="54"
      viewBox="0 0 244 54"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      className={cn("shrink-0 text-current", className)}
      aria-hidden="true"
      focusable="false"
    >
      <path
        fill="currentColor"
        fillRule="evenodd"
        clipRule="evenodd"
        transform="translate(0 5) scale(0.172)"
        d="M35.8 39.9L41.2 47.8L42.0 47.8L43.9 51.5L97.5 127.4L111.3 110.2L111.7 110.6L85.3 169.2L85.6 170.4L84.1 171.1L69.9 191.0L69.2 191.2L80.0 165.7L80.4 163.4L81.3 162.5L86.6 149.9L86.2 149.0L87.0 149.0L91.8 136.6L30.9 215.9L87.7 215.9L115.4 179.2L116.0 177.3L117.1 176.9L127.8 162.7L168.1 215.9L224.7 215.9L158.5 129.1L146.4 144.9L144.1 146.9L163.4 99.1L148.8 115.4L149.2 116.0L132.1 128.7L161.5 91.1L161.5 90.0L162.7 89.6L168.9 81.5L168.5 81.1L169.4 81.0L169.2 80.2L170.9 79.1L171.3 77.8L172.4 77.2L185.0 61.3L176.9 83.2L176.7 85.1L175.8 86.2L165.9 113.4L166.4 113.2L166.6 114.5L165.3 114.3L164.9 115.8L165.9 116.8L181.8 94.5L182.2 92.6L183.3 92.4L183.7 91.1L184.6 90.5L199.2 69.7L199.4 68.6L200.0 68.6L199.6 68.0L200.3 67.1L200.9 67.3L200.3 66.9L201.8 66.0L207.6 57.7L207.3 57.5L213.3 49.9L214.2 47.4L215.9 46.1L220.2 39.9L167.2 39.9L128.2 93.5L89.8 39.9Z"
      />
      <text
        x="52"
        y="41"
        fill="currentColor"
        style={{
          fontFamily: "-apple-system, 'Segoe UI', 'Inter', sans-serif",
          fontSize: "38px",
          fontWeight: 650,
          letterSpacing: "-0.5px",
        }}
      >
        XenCode
      </text>
    </svg>
  );
}
