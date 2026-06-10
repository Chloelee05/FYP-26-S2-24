import { useState, useEffect } from 'react';
import { Clock } from 'lucide-react';

export default function CountdownTimer({ endTime }) {
  const [remaining, setRemaining] = useState('');

  useEffect(() => {
    const calc = () => {
      const diff = new Date(endTime) - new Date();
      if (diff <= 0) { setRemaining('Ended'); return; }
      const h = Math.floor(diff / 3600000);
      const m = Math.floor((diff % 3600000) / 60000);
      const s = Math.floor((diff % 60000) / 1000);
      setRemaining(`${h}h ${m}m ${s}s`);
    };
    calc();
    const id = setInterval(calc, 1000);
    return () => clearInterval(id);
  }, [endTime]);

  return (
    <div className="flex items-center gap-1 text-gray-600">
      <Clock size={16} />
      <span className="font-medium">{remaining}</span>
    </div>
  );
}
