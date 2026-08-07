import { useState, useEffect } from 'react';
import { MousePointerClick, Search, ArrowLeft } from 'lucide-react';
import { getRecommendationAttribution } from '../../api/admin';
import { apiErrorMessage } from '../../utils/apiError';

/**
 * ADMIN-only counterpart to the "why this?" panel on the landing page.
 *
 * The public page shows aggregates and masked names; this shows the individual
 * accounts behind them. The endpoint enforces the ADMIN role server side, so this
 * component never becomes a data leak if it is rendered somewhere else by mistake.
 *
 * Drop it into an admin page as <RecommendationAttributionPanel />. Currently rendered at the
 * bottom of AdminAnalytics, so it lives under "/admin/analytics".
 *
 * Two views from one endpoint, GET recommendation attribution. With no argument it returns
 * the overview: most clicked recommended listings and most searched keywords. Passing an
 * auction id returns the per event detail for that listing, which is what a click on a row
 * loads. Signed-out visitors are counted as well, and appear without a username.
 */
export default function RecommendationAttributionPanel() {
  const [overview, setOverview] = useState(null);
  const [detail, setDetail] = useState(null);
  const [selected, setSelected] = useState(null);
  const [error, setError] = useState('');

  useEffect(() => {
    getRecommendationAttribution()
      .then(r => setOverview(r.data))
      .catch(err => setError(apiErrorMessage(err, 'Could not load recommendation attribution.')));
  }, []);

  // Drills into one listing. selected is set before the request so the Back button and the
  // heading are correct while the detail is still loading; detail is cleared first so the
  // previous listing's rows are not shown under the new title.
  const openAuction = async (auction) => {
    setError('');
    setSelected(auction);
    setDetail(null);
    try {
      const r = await getRecommendationAttribution(auction.auctionId, 50);
      setDetail(r.data);
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not load that auction’s attribution.'));
    }
  };

  const when = (iso) => (iso ? new Date(iso).toLocaleString() : '—');

  return (
    <div className="card p-5 mb-6">
      <div className="flex items-start justify-between gap-4 mb-4">
        <div>
          <h2 className="font-bold text-ink-900 mb-1">Recommendation Attribution</h2>
          <p className="text-sm text-ink-400">
            Who clicked a recommendation and which keywords surfaced it. Visitors only ever
            see the totals and masked names — this per-user view is admin-only.
          </p>
        </div>
        {selected && (
          <button
            type="button"
            onClick={() => { setSelected(null); setDetail(null); }}
            className="btn-secondary btn-sm shrink-0"
          >
            <ArrowLeft size={14} /> Back
          </button>
        )}
      </div>

      {error && <div className="alert-error mb-4"><span>{error}</span></div>}

      {!selected ? (
        <div className="grid md:grid-cols-2 gap-6">
          <div>
            <h3 className="flex items-center gap-2 text-xs font-bold uppercase tracking-wide text-ink-400 mb-2">
              <MousePointerClick size={13} /> Most clicked recommendations
            </h3>
            {overview?.topAuctions?.length ? (
              <table className="table-clean">
                <thead>
                  <tr><th>Listing</th><th>Clicks</th><th>Impressions</th></tr>
                </thead>
                <tbody>
                  {overview.topAuctions.map(a => (
                    <tr key={a.auctionId}>
                      <td>
                        <button
                          type="button"
                          onClick={() => openAuction(a)}
                          className="link-subtle text-left"
                        >
                          {a.title ?? `Auction ${a.auctionId}`}
                        </button>
                      </td>
                      <td className="tabular-nums">{a.clicks} ({a.distinctClickers} people)</td>
                      <td className="tabular-nums">{a.impressions}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <p className="text-sm text-ink-400">No recommendation events recorded yet.</p>
            )}
          </div>

          <div>
            <h3 className="flex items-center gap-2 text-xs font-bold uppercase tracking-wide text-ink-400 mb-2">
              <Search size={13} /> Most searched keywords
            </h3>
            {overview?.topKeywords?.length ? (
              <table className="table-clean">
                <thead>
                  <tr><th>Keyword</th><th>Searches</th><th>People</th></tr>
                </thead>
                <tbody>
                  {overview.topKeywords.map(k => (
                    <tr key={k.keyword}>
                      <td className="font-medium text-ink-800">{k.keyword}</td>
                      <td className="tabular-nums">{k.searches}</td>
                      <td className="tabular-nums">{k.searchers}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <p className="text-sm text-ink-400">No searches recorded yet.</p>
            )}
          </div>
        </div>
      ) : !detail ? (
        <p className="text-sm text-ink-400">Loading attribution…</p>
      ) : (
        <div className="grid md:grid-cols-2 gap-6">
          <div>
            <h3 className="text-xs font-bold uppercase tracking-wide text-ink-400 mb-2">
              Events on “{selected.title ?? selected.auctionId}”
            </h3>
            {detail.events?.length ? (
              <table className="table-clean">
                <thead>
                  <tr><th>User</th><th>Event</th><th>Keyword</th><th>When</th></tr>
                </thead>
                <tbody>
                  {detail.events.map((e, i) => (
                    <tr key={i}>
                      <td>{e.username ?? 'Signed-out visitor'}</td>
                      <td>{e.eventType}</td>
                      <td>{e.sourceKeyword ?? '—'}</td>
                      <td className="text-ink-400 whitespace-nowrap">{when(e.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <p className="text-sm text-ink-400">No events for this listing.</p>
            )}
          </div>

          <div>
            <h3 className="text-xs font-bold uppercase tracking-wide text-ink-400 mb-2">
              Searches that match this listing
            </h3>
            {detail.searches?.length ? (
              <table className="table-clean">
                <thead>
                  <tr><th>User</th><th>Keyword</th><th>When</th></tr>
                </thead>
                <tbody>
                  {detail.searches.map((s, i) => (
                    <tr key={i}>
                      <td>{s.username ?? 'Signed-out visitor'}</td>
                      <td className="font-medium text-ink-800">{s.keyword}</td>
                      <td className="text-ink-400 whitespace-nowrap">{when(s.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <p className="text-sm text-ink-400">No matching searches recorded.</p>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
