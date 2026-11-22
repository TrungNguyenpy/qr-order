"use client";

export function Stepper({ value, min = 0, max, onChange }: { value: number; min?: number; max: number; onChange: (v: number) => void }) {
  const btn = "flex size-9 items-center justify-center rounded-ctl border border-line-strong text-lg leading-none hover:bg-surface disabled:text-ink-3 disabled:hover:bg-transparent";
  return (
    <div className="inline-flex items-center gap-1">
      <button type="button" className={btn} aria-label="Giảm" disabled={value <= min} onClick={() => onChange(value - 1)}>
        −
      </button>
      <span className="w-8 text-center text-[15px] font-medium tabular-nums" aria-live="polite">
        {value}
      </span>
      <button type="button" className={btn} aria-label="Tăng" disabled={value >= max} onClick={() => onChange(value + 1)}>
        +
      </button>
    </div>
  );
}
