# Changelog

## Unreleased

- Add `mock_id` to startup attempt, startup rejection, and pod deletion counters. Valid IDs in the merged baseline and user mock configuration receive their own label; unconfigured or invalid IDs use `unknown`. Sum over `mock_id` to retain existing counter totals.
- Add `mock_fleet_start_duration_by_mock_seconds_count`, `_sum`, and `_max` with `outcome` and `mock_id` labels. The existing aggregate startup duration histogram keeps its labels and buckets unchanged; the new per-mock timer has no buckets.
