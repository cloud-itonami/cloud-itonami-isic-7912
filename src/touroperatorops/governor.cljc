(ns touroperatorops.governor
  "TourOperatorGovernor -- the independent compliance layer that earns
  the TourOperatorOpsAdvisor the right to commit. The advisor has no
  notion of whether a tour-booking record is actually registered and
  verified, whether its own proposed `:effect` secretly claims a
  direct actuation instead of a mere proposal, or whether it has
  silently drifted into a permanently out-of-scope decision area, so
  this MUST be a separate system able to *reject* a proposal and fall
  back to HOLD.

  This actor's scope is deliberately narrow -- OPERATIONS COORDINATION
  ONLY (tour-record logging, tour-operation scheduling, local-vendor/
  guide settlement coordination, traveler-safety-concern flagging). It
  NEVER performs or authorizes:
    - directly finalizing a traveler-safety-clearance decision (e.g.
      clearing an excursion as safe after a reported hazard)
    - directly issuing a resumption/go-ahead order for a held excursion
    - overriding a traveler-safety authority's decision

  This is the Wave 4 person-facing-service safety guardrail
  (ADR-2607152500): tour operations have a direct traveler-safety
  dimension (excursion safety, guide-led activities) -- the closed op
  allowlist NEVER includes any op that directly finalizes a
  traveler-safety-clearance decision; those are always either a hard
  permanent block or an always-escalate op, never auto-commit-eligible.

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Tour unverified            -- the target tour-booking record
                                      must exist AND be independently
                                      confirmed `:registered?`/
                                      `:verified?` in the store before
                                      ANY proposal for it may commit or
                                      even escalate. Never trusts a
                                      proposal's own claim about the
                                      tour -- re-derived from the
                                      tour's own store record, the same
                                      'ground truth, not self-report'
                                      discipline every sibling actor's
                                      governor uses.
    2. Effect not :propose        -- every proposal's `:effect` MUST
                                      be `:propose`. Any other effect
                                      value is, by construction, a
                                      claim to directly actuate/commit
                                      outside governance -- HARD block,
                                      not merely low-confidence.
    3. Scope exclusion            -- ANY proposal (regardless of op)
                                      whose op is outside the closed
                                      four-op allowlist, or whose
                                      rationale, summary, citations or
                                      draft value touches directly
                                      finalizing a traveler-safety-
                                      clearance decision (e.g. clearing
                                      an excursion as safe, issuing a
                                      resumption/go-ahead order for a
                                      held excursion, or overriding a
                                      traveler-safety authority's
                                      decision), is a HARD, PERMANENT
                                      block -- this actor's charter
                                      excludes that territory
                                      structurally, not as a rollout
                                      milestone. Evaluated
                                      UNCONDITIONALLY on every
                                      proposal. An op outside the
                                      closed four-op allowlist is the
                                      SAME failure mode (an advisor
                                      proposing something it was never
                                      authorized to propose) and is
                                      folded into this same check.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is `:flag-traveler-safety-concern` (ALWAYS escalates to a human,
  regardless of confidence, regardless of how clean the proposal
  otherwise is), OR a `:coordinate-vendor-settlement` proposal whose
  estimated amount exceeds `high-cost-threshold`.
  `touroperatorops.phase` independently agrees:
  `:flag-traveler-safety-concern` is never a member of any phase's
  `:auto` set either -- two layers, not one."
  (:require [clojure.string :as str]
            [touroperatorops.store :as store]))

(def confidence-floor 0.6)

(def high-cost-threshold
  "A `:coordinate-vendor-settlement` proposal whose `:value
  :estimated-amount` exceeds this amount (USD) ALWAYS escalates to a
  human, regardless of confidence -- routine local-guide/vendor
  settlements sit well under this, so this only catches unusually
  large payouts."
  2000)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`).
  Per the Wave 4 person-facing-service safety guardrail
  (ADR-2607152500), NO op in this set may directly finalize a
  traveler-safety-clearance decision -- every op here is `:effect
  :propose` only, and `:flag-traveler-safety-concern` always escalates
  rather than ever auto-committing."
  #{:log-tour-record :schedule-tour-operation
    :coordinate-vendor-settlement :flag-traveler-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-traveler-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- directly finalizing a
  traveler-safety-clearance decision, directly issuing a resumption/
  go-ahead order for a held excursion, or overriding a
  traveler-safety authority's decision. Scanned across the proposal's
  op/summary/rationale/cites/value, never trusting the advisor's own
  framing of its intent.

  Deliberately phrased as EXECUTION/FINALIZATION phrases (verb +
  object), not bare nouns like \"safety\" alone -- a legitimate
  `:flag-traveler-safety-concern` proposal must be free to *describe*
  a hazard, an incident, or a medical concern without tripping this
  gate (see `touroperatorops.governor-test`'s own
  `legitimate-traveler-safety-concern-is-not-scope-excluded` and
  `default-mock-advisor-proposals-never-self-trip-scope-exclusion`);
  only a proposal that claims to *actually finalize* the
  traveler-safety-clearance decision is blocked here."
  ["finalize the excursion-safety clearance" "finalize excursion-safety clearance"
   "finalize the traveler-safety clearance" "finalize traveler-safety clearance"
   "clear the excursion as safe" "clear excursion as safe"
   "issue a safety clearance" "issue safety clearance"
   "certify the excursion as safe" "certify excursion as safe"
   "authorize the excursion to resume" "authorize resumption of the excursion"
   "clear the guide to proceed despite the hazard" "declare the hazard resolved"
   "override traveler safety authority" "override the traveler safety authority"
   "override safety authority" "override the safety authority"
   "bypass traveler safety authority" "bypass safety authority"
   "安全クリアランスを確定" "安全クリアランスを発行" "エクスカーションを安全と確定"
   "危険なしと判断を確定" "ツアー再開を許可" "再開を許可"
   "安全当局の判断を覆" "安全当局の決定を無視"])

;; ----------------------------- checks -----------------------------

(defn- tour-unverified-violations
  "The target tour-booking record must exist AND be independently
  `:registered?`/`:verified?` in the store -- never trust the
  proposal's own `:tour-id` claim without a store lookup."
  [{:keys [tour-id]} st]
  (let [r (store/tour st tour-id)]
    (when-not (and r (:registered? r) (:verified? r))
      [{:rule :tour-unverified
        :detail (str tour-id " は未登録または未検証のツアー -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches directly finalizing a traveler-safety-
  clearance decision, directly issuing a resumption/go-ahead order for
  a held excursion, or overriding a traveler-safety authority's
  decision, regardless of confidence or how clean every other check
  is. Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "旅行者安全クリアランスの直接確定/エクスカーション再開許可の直接発行/旅行者安全当局の判断の上書きは永久に禁止"}])))

(defn- high-cost-vendor-settlement?
  "A `:coordinate-vendor-settlement` proposal whose `:value
  :estimated-amount` exceeds `high-cost-threshold` ALWAYS escalates,
  regardless of confidence."
  [proposal]
  (and (= :coordinate-vendor-settlement (:op proposal))
       (some-> (get-in proposal [:value :estimated-amount])
               (> high-cost-threshold))))

(defn check
  "Censors a TourOperatorOpsAdvisor proposal against the governor
  rules. Returns {:ok? bool :violations [..] :confidence c :escalate?
  bool :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [tour-id (or (:tour-id proposal) (:tour-id request))
        hard (into []
                   (concat (tour-unverified-violations {:tour-id tour-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                              (high-cost-vendor-settlement? proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :tour-id    (:tour-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
