import { FeedbackState } from '../../../components/ui/FeedbackState'

/** No staff provider is implemented yet (#14). Do not substitute synthetic identity data. */
export function StaffProfileUnavailable() {
  return (
    <FeedbackState title="Staff profile unavailable">
      Staff profile access is not available in this release. No personal staff details are loaded or displayed here.
    </FeedbackState>
  )
}
