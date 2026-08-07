import {
  Smartphone, Laptop, Cpu, Camera, Headphones, Gamepad2, ToyBrick, Puzzle, Watch, Gem,
  Crown, ShoppingBag, Footprints, Glasses, Shirt, Sparkles, HeartPulse, Trophy, Dumbbell,
  Bike, Car, Building2, Sofa, House, Flower2, Wrench, Book, Palette, Landmark, Baby, Dog,
  Utensils, Plane, Briefcase, Gift, Package, Tag,
} from 'lucide-react';

/**
 * Built-in icon per category name, so a category with no uploaded picture still looks
 * deliberate the moment an admin creates it.
 *
 * Keywords match at a word start rather than anywhere in the string: a plain substring
 * test finds "men" inside "equipment" and "art" inside "smart", which mis-icons real
 * category names. First entry wins, so narrow keywords sit above broad ones — "Furniture
 * & Home Living" must reach `furniture` before `home`.
 *
 * Tint classes are written out in full because Tailwind scans source text for class
 * names; a composed string like `bg-${colour}-50` would never be generated.
 */
const LOOKS = [
  [['mobile', 'phone', 'gadget'],                        Smartphone, 'bg-blue-50 text-blue-600'],
  [['computer', 'laptop', 'pc', 'tech'],                 Laptop,     'bg-indigo-50 text-indigo-600'],
  [['electronic'],                                       Cpu,        'bg-indigo-50 text-indigo-600'],
  [['camera', 'photography'],                            Camera,     'bg-zinc-100 text-zinc-600'],
  [['headphone', 'audio', 'music', 'tv'],                Headphones, 'bg-violet-50 text-violet-600'],
  [['game', 'gaming', 'console'],                        Gamepad2,   'bg-fuchsia-50 text-fuchsia-600'],
  [['toy', 'lego'],                                      ToyBrick,   'bg-amber-50 text-amber-600'],
  [['collectib', 'figure', 'memorabilia'],               Puzzle,     'bg-amber-50 text-amber-600'],
  [['watch', 'timepiece'],                               Watch,      'bg-slate-100 text-slate-600'],
  [['jewel', 'jewellery', 'gem', 'diamond'],             Gem,        'bg-pink-50 text-pink-600'],
  [['luxury', 'designer', 'premium'],                    Crown,      'bg-amber-50 text-amber-600'],
  [['bag', 'backpack', 'luggage', 'purse'],              ShoppingBag,'bg-rose-50 text-rose-600'],
  [['shoe', 'sneaker', 'footwear'],                      Footprints, 'bg-orange-50 text-orange-600'],
  [['accessor', 'glasses', 'eyewear'],                   Glasses,    'bg-teal-50 text-teal-600'],
  [['fashion', 'clothing', 'apparel', 'shirt', 'dress'], Shirt,      'bg-purple-50 text-purple-600'],
  [['beauty', 'cosmetic', 'skincare', 'makeup'],         Sparkles,   'bg-pink-50 text-pink-600'],
  [['health', 'medical', 'wellness'],                    HeartPulse, 'bg-red-50 text-red-600'],
  [['fitness', 'gym', 'exercise'],                       Dumbbell,   'bg-emerald-50 text-emerald-600'],
  [['sport', 'outdoor'],                                 Trophy,     'bg-emerald-50 text-emerald-600'],
  [['bike', 'bicycle', 'cycling'],                       Bike,       'bg-emerald-50 text-emerald-600'],
  [['car', 'vehicle', 'motor', 'auto'],                  Car,        'bg-blue-50 text-blue-600'],
  [['property', 'estate', 'apartment', 'land'],          Building2,  'bg-green-50 text-green-600'],
  [['furniture', 'sofa', 'living'],                      Sofa,       'bg-orange-50 text-orange-600'],
  [['home', 'household', 'kitchen', 'appliance'],        House,      'bg-orange-50 text-orange-600'],
  [['garden', 'plant', 'flower'],                        Flower2,    'bg-green-50 text-green-600'],
  [['tool', 'hardware', 'diy'],                          Wrench,     'bg-slate-100 text-slate-600'],
  [['book', 'magazine', 'stationery'],                   Book,       'bg-sky-50 text-sky-600'],
  [['art', 'painting', 'craft'],                         Palette,    'bg-rose-50 text-rose-600'],
  [['antique', 'vintage', 'heritage'],                   Landmark,   'bg-amber-50 text-amber-600'],
  [['baby', 'kid', 'infant', 'child'],                   Baby,       'bg-sky-50 text-sky-600'],
  [['pet', 'dog', 'cat', 'animal'],                      Dog,        'bg-amber-50 text-amber-600'],
  [['food', 'grocery', 'snack', 'beverage', 'drink'],    Utensils,   'bg-orange-50 text-orange-600'],
  [['travel', 'ticket', 'event', 'flight'],              Plane,      'bg-sky-50 text-sky-600'],
  [['service', 'repair', 'job'],                         Briefcase,  'bg-slate-100 text-slate-600'],
  [['trade', 'deal', 'voucher', 'gift'],                 Gift,       'bg-rose-50 text-rose-600'],
  [['other', 'misc'],                                    Package,    'bg-ink-100 text-ink-500'],
];

const FALLBACK = { Icon: Tag, tint: 'bg-ink-100 text-ink-500' };

/** Escapes regex metacharacters so a name like "C++" cannot break the matcher. */
const escape = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** The icon component and tint classes a category name maps to. */
export function categoryLook(name) {
  const value = (name ?? '').toLowerCase();
  if (!value) return FALLBACK;
  for (const [keywords, Icon, tint] of LOOKS) {
    if (keywords.some(kw => new RegExp(`\\b${escape(kw)}`).test(value))) {
      return { Icon, tint };
    }
  }
  return FALLBACK;
}
