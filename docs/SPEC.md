# Library specification

## Input evidence

The caller supplies evidence that has already been reconstructed and validated by an evidence reader. Each source contains `id`, `label`, `fingerprint`, and `evidence`. Identities and content fingerprints are unique among supplied sources.

The `evidence` object contains these collections:

| Collection | Records |
| --- | --- |
| `runs` | Test runs containing their original `id`. |
| `sequences` | Test sequences containing `id` and their parent `test_run`. |
| `observations` | Original observations, including `recorded_state`, widgets, capture status, and available actions. |
| `actions` | Original action descriptions. |
| `executions` | Records containing `execution`, `status`, and, when recorded, `resultingObservation`. |

`execution` contains the original action execution: its `id`, `test_sequence`, `source_observation`, `action_description`, `target_widget`, and `parameters`. Statuses retain the evidence terminology, including `awaiting_execution_result` and `awaiting_resulting_observation`.

Collections use arrays of records. The shared conformance input uses this same collection structure. An original JSONL trace must first be read by its evidence library; it is different from this reconstructed input.

## Group observations

Rules select state properties, widget properties, filtering, hierarchy, order, and string normalization. The preview contains the applied `definition`, `policyFingerprint`, `interpreterVersion`, `inputScope`, `groups`, and `unmatched` observations.

Each group retains compared values, observation references, and `widgetCorrespondences`. Repeated matching widgets in one observation make the correspondence ambiguous. An observation with missing required values or unsuitable comparison values retains its reference and an explanation in `unmatched`.

## Construct the model

Model construction verifies the original source fingerprints and selected runs. The result contains:

| Field | Meaning |
| --- | --- |
| `nodes` | Abstract states with their original observation members; unmatched observations have their own node and explanation. |
| `edges` | Executed transitions and separate observation-order links. Each edge retains supporting execution or observation references. |
| `unlinkedExecutions` | Executions without a completed connection, retaining their original status and an explanation. |
| `actionEvidence` | Present when action rules are supplied. Contains abstract actions, availability, execution relationships, ambiguity, and unmatched action records. |

Action matching stays within each abstract source state. Recorded availability links remain available when actual input differs from the planned value. An action without a matching execution retains the availability evidence; its outcome remains unknown.

Inputs remain unchanged. Returned records are independent copies. Generated identifiers connect records within the constructed result; cross-language verification compares memberships and supporting references.

## Implementation responsibilities

| Class | Responsibility |
| --- | --- |
| `Abstraction` | Public parsing, preview, and model-construction API. |
| `Model` | Verify the preview's evidence scope and construct transitions and observation-order links. |
| `Rules` / `Fields` / `Url` | Validate definitions, select properties, and normalize comparison values. |
| `Projection` | Filter widgets and compare flat collections or parent-child hierarchies. |
| `EvidenceIndex` | Find selected runs and source-qualified evidence records. |
| `Actions` | Match availability and executions while retaining ambiguity and recorded links. |
| `Json` | Comparison identities, JSON handling, and accumulated processing budgets. |

The public API coordinates these responsibilities. Processing follows the limits and semantics defined in the [shared specification](https://github.com/ferpasri/testar-abstraction-specs/blob/main/docs/SPEC.md).

Rule or scope errors reject the operation. Processing-budget exhaustion also rejects the operation. Unsuitable observation or action values produce unmatched records with explanations.
