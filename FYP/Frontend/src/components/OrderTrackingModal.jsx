import { Package, CreditCard, Truck, MapPin, CheckCircle2 } from 'lucide-react';
import Modal from './Modal';

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
    <Modal
      title="Track order"
      subtitle={`${order.auctionTitle} · Order #${order.id}`}
      icon={Truck}
      onClose={onClose}
      size="md"
    >
      <div className="p-6">
          {STEPS.map((step, i) => {
            const done = i <= active;
            const current = i === active;
            const Icon = step.icon;
            const ts = stepTime(order, step.key);
            return (
              <div key={step.key} className="flex gap-3.5">
                <div className="flex flex-col items-center">
                  <div className={`w-9 h-9 rounded-full flex items-center justify-center shrink-0 transition-colors ${
                    done ? 'bg-emerald-500 text-white shadow-sm' : 'bg-ink-100 text-ink-400'
                  } ${current ? 'ring-4 ring-emerald-100' : ''}`}>
                    <Icon size={15} />
                  </div>
                  {i < STEPS.length - 1 && (
                    <div className={`w-0.5 flex-1 min-h-[22px] rounded-full ${i < active ? 'bg-emerald-400' : 'bg-ink-200'}`} />
                  )}
                </div>
                <div className="pb-5 pt-1.5">
                  <p className={`text-sm font-semibold ${done ? 'text-ink-900' : 'text-ink-400'}`}>{step.label}</p>
                  {ts && (
                    <p className="text-xs text-ink-400 mt-0.5">
                      {new Date(ts).toLocaleString()}
                    </p>
                  )}
                  {current && order.status === 'PAID' && order.shippingStatus && (
                    <p className="text-xs font-medium text-primary-600 mt-0.5">In progress…</p>
                  )}
                </div>
              </div>
            );
          })}
      </div>
    </Modal>
  );
}
