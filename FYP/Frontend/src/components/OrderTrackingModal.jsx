import { X, Package, CreditCard, Truck, MapPin, CheckCircle2 } from 'lucide-react';

const STEPS = [
  { key: 'placed', label: 'Order placed', icon: Package },
  { key: 'paid', label: 'Payment confirmed', icon: CreditCard },
  { key: 'PREPARING', label: 'Seller preparing', icon: Package },
  { key: 'SHIPPED', label: 'Shipped', icon: Truck },
  { key: 'IN_TRANSIT', label: 'Out for delivery', icon: MapPin },
  { key: 'DELIVERED', label: 'Delivered', icon: CheckCircle2 },
  { key: 'completed', label: 'Receipt confirmed', icon: CheckCircle2 },
];

function stepIndex(order) {
  if (order.status === 'COMPLETED') return 6;
  const ship = (order.shippingStatus || '').toUpperCase();
  if (ship === 'DELIVERED') return 5;
  if (ship === 'IN_TRANSIT') return 4;
  if (ship === 'SHIPPED') return 3;
  if (ship === 'PREPARING' || order.status === 'PAID') return 2;
  if (order.status === 'PENDING_PAYMENT') return 0;
  return 1;
}

function stepTime(order, key) {
  if (key === 'placed') return order.createdAt;
  if (key === 'paid') return order.paidAt;
  if (key === 'completed') return order.completedAt;
  if (['PREPARING', 'SHIPPED', 'IN_TRANSIT', 'DELIVERED'].includes(key)
      && (order.shippingStatus || '').toUpperCase() === key) {
    return order.shippingUpdatedAt;
  }
  return null;
}

export default function OrderTrackingModal({ order, onClose }) {
  if (!order) return null;
  const active = stepIndex(order);

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4" onClick={onClose}>
      <div className="bg-white rounded-2xl shadow-xl max-w-md w-full p-6" onClick={e => e.stopPropagation()}>
        <div className="flex items-start justify-between mb-4">
          <div>
            <h2 className="font-bold text-gray-900">Track order</h2>
            <p className="text-sm text-gray-500">{order.auctionTitle}</p>
            <p className="text-xs text-gray-400 mt-0.5">Order #{order.id}</p>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600"><X size={18} /></button>
        </div>

        <div className="space-y-0">
          {STEPS.map((step, i) => {
            const done = i <= active;
            const current = i === active;
            const Icon = step.icon;
            const ts = stepTime(order, step.key);
            return (
              <div key={step.key} className="flex gap-3">
                <div className="flex flex-col items-center">
                  <div className={`w-8 h-8 rounded-full flex items-center justify-center shrink-0 ${
                    done ? 'bg-green-500 text-white' : 'bg-gray-100 text-gray-400'
                  } ${current ? 'ring-2 ring-green-200' : ''}`}>
                    <Icon size={14} />
                  </div>
                  {i < STEPS.length - 1 && (
                    <div className={`w-0.5 flex-1 min-h-[24px] ${i < active ? 'bg-green-400' : 'bg-gray-200'}`} />
                  )}
                </div>
                <div className="pb-5 pt-1">
                  <p className={`text-sm font-medium ${done ? 'text-gray-900' : 'text-gray-400'}`}>{step.label}</p>
                  {ts && (
                    <p className="text-xs text-gray-400">
                      {new Date(ts).toLocaleString()}
                    </p>
                  )}
                  {current && order.status === 'PAID' && order.shippingStatus && (
                    <p className="text-xs text-blue-500 mt-0.5">In progress…</p>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
