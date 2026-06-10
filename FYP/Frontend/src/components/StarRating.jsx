export default function StarRating({ value = 0, max = 5, onChange, size = 20 }) {
  return (
    <div className="flex gap-0.5">
      {Array.from({ length: max }, (_, i) => (
        <button
          key={i}
          type="button"
          onClick={() => onChange && onChange(i + 1)}
          className={`text-${i < value ? 'yellow-400' : 'gray-200'} transition-colors ${onChange ? 'cursor-pointer hover:text-yellow-300' : 'cursor-default'}`}
          style={{ fontSize: size, lineHeight: 1 }}
        >
          ★
        </button>
      ))}
    </div>
  );
}
