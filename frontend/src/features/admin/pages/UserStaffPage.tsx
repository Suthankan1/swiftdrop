import { useEffect, useId, useMemo, useState } from 'react'
import { Link, NavLink, useNavigate, useParams } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../../auth/context'
import { adminFailure, canRecover, canSuspend, createAdminManagementApi, isProfileId, provisionProblem, staffRoles, staffStatuses,
  type AdminManagementApi, type AdminPage, type AdminStaff, type ProvisionStaff, type StaffCommand, type StaffFilters } from '../api/userStaff'
import { useAdminCommand, useAdminQuery, type AdminScope } from '../hooks/useAdminManagement'
import { StaffConfirmation } from '../components/StaffConfirmation'
import { Button } from '../../../components/ui/Button'
import { Badge as UiBadge } from '../../../components/ui/Badge'

type Section = 'users' | 'staff' | 'detail'
export function UserStaffPage({ section, api }: { section: Section; api?: AdminManagementApi }) {
  const auth = useAuth()
  const { profileId } = useParams()
  const defaultApi = useMemo(() => createAdminManagementApi({ getAccessToken: auth.getAccessToken }), [auth.getAccessToken])
  if (auth.snapshot.status !== 'authenticated' || !auth.snapshot.roles.includes('ADMIN')) return <p role="alert">User and staff management requires an authenticated ADMIN session.</p>
  return <AdminWorkspace key={`${auth.snapshot.subject}:${auth.snapshot.roles.join(',')}`} section={section} id={profileId} api={api ?? defaultApi} />
}
function AdminWorkspace({ section, id, api }: { section: Section; id?: string; api: AdminManagementApi }) {
  const auth = useAuth(), cache = useQueryClient(), sessionId = useId()
  const subject = auth.snapshot.subject ?? ''
  const prefix = useMemo(() => ['private', 'admin-user-staff', subject, sessionId], [subject, sessionId])
  const [denied, setDenied] = useState<string | null>(null)
  useEffect(() => () => { void cache.cancelQueries({ queryKey: prefix }); cache.removeQueries({ queryKey: prefix }) }, [cache, prefix])
  const scope: AdminScope = { prefix, fail: async (error, signal) => {
    if (signal.aborted) return
    const failure = adminFailure(error)
    if (failure.kind === 'auth-required' || failure.kind === 'forbidden') {
      setDenied(failure.message)
      void cache.cancelQueries({ queryKey: prefix }); cache.removeQueries({ queryKey: prefix })
      if (failure.kind === 'auth-required') await auth.handleSessionExpiry()
    }
  } }
  return <div className="page-container admin-management">
    <Link to="/admin">Back to administration</Link>
    <header className="staff-heading"><p className="eyebrow">Admin space</p><h1 className="page-heading">{section === 'users' ? 'User management' : section === 'staff' ? 'Staff management' : 'Staff details'}</h1>
      <p className="muted">Manage Accounts business profiles and controlled staff access.</p></header>
    {denied ? <p role="alert">{denied}</p> : <>
      <nav className="admin-management-toolbar" aria-label="User and staff administration"><NavLink to="/admin/users">Users</NavLink><NavLink to="/admin/staff" end>Staff</NavLink></nav>
      {section === 'users' ? <Users api={api} scope={scope} /> : section === 'staff' ? <Staff api={api} scope={scope} /> : id && isProfileId(id) ? <StaffDetails key={id} id={id} api={api} scope={scope} /> : <p role="alert">Invalid staff profile ID.</p>}
    </>}
  </div>
}
function ReadError({ error, retry, busy }: { error: unknown; retry: () => void; busy: boolean }) {
  return <div role="alert"><p>{adminFailure(error).message}</p><Button variant="secondary" size="sm" disabled={busy} onClick={retry}>Retry read</Button></div>
}
function Pagination({ data, busy, change }: { data: AdminPage<unknown>; busy: boolean; change: (page: number) => void }) {
  return <nav className="admin-management-toolbar" aria-label="Result pages">
    <Button variant="secondary" size="sm" disabled={data.first || busy} onClick={() => change(data.page - 1)}>Previous page</Button>
    <span>Page {data.page + 1} · {data.totalPages} available pages · {data.totalElements} records</span>
    {data.page > 0 && data.page >= data.totalPages && <Button variant="secondary" size="sm" disabled={busy} onClick={() => change(0)}>Return to first page</Button>}
    <Button variant="secondary" size="sm" disabled={data.last || busy} onClick={() => change(data.page + 1)}>Next page</Button>
  </nav>
}
function Badge({ value }: { value: string }) {
  const isGood = value === 'ACTIVE' || value === 'COMPLETED' || value === 'CONFIRMED' || value === 'SUCCESS'
  const isWarn = value === 'PENDING_RETRY' || value === 'INACTIVE' || value === 'PENDING'
  const isDanger = value === 'SUSPENDED' || value === 'FAILED' || value === 'DENIED'
  const variant = isGood ? 'success' : isWarn ? 'warning' : isDanger ? 'danger' : 'neutral'
  return <UiBadge variant={variant} size="sm" pulse={isGood}>{value.replaceAll('_', ' ')}</UiBadge>
}
function Users({ api, scope }: { api: AdminManagementApi; scope: AdminScope }) {
  const [page, setPage] = useState(0)
  const query = useAdminQuery(scope, ['users', page], signal => api.users(page, signal))
  return <section aria-label="Accounts users" aria-busy={query.isFetching}>
    <div className="admin-management-toolbar"><h2>Accounts users</h2><Button variant="secondary" size="sm" disabled={query.isFetching} onClick={() => { void query.refetch() }}>Refresh users</Button></div>
    <p className="muted">Accounts exposes paged summaries. User search, account status, roles and user detail are not available from this provider.</p>
    {query.isFetching && <p role="status">Loading users…</p>}
    {query.isError && <ReadError error={query.error} busy={query.isFetching} retry={() => { void query.refetch() }} />}
    {query.data && !query.isError && <>
      {!query.data.content.length ? <p role="status">No users on this page.</p> : <div className="admin-management-scroll" tabIndex={0} role="region" aria-label="Scrollable users table"><table>
        <caption>Accounts user summaries</caption><thead><tr><th scope="col">Name</th><th scope="col">Account profile ID</th><th scope="col">Email</th><th scope="col">Created</th></tr></thead>
        <tbody>{query.data.content.map(user => <tr key={user.profileId}><td>{user.fullName ?? 'Not provided'}</td><td>{user.profileId}</td><td>{user.email ?? 'Not provided'}</td><td><time dateTime={user.createdAt}>{user.createdAt}</time></td></tr>)}</tbody>
      </table></div>}
      <Pagination data={query.data} busy={query.isFetching} change={setPage} />
    </>}
  </section>
}
function Staff({ api, scope }: { api: AdminManagementApi; scope: AdminScope }) {
  const [page, setPage] = useState(0), [filters, setFilters] = useState<StaffFilters>({})
  const [role, setRole] = useState(''), [status, setStatus] = useState(''), [zone, setZone] = useState('')
  const query = useAdminQuery(scope, ['staff', page, filters], signal => api.staff(page, filters, signal))
  return <>
    <section aria-label="Staff directory" aria-busy={query.isFetching}>
      <div className="admin-management-toolbar"><h2>Staff directory</h2><Button variant="secondary" size="sm" disabled={query.isFetching} onClick={() => { void query.refetch() }}>Refresh staff</Button></div>
      <form className="admin-management-filters" onSubmit={event => {
        event.preventDefault(); setPage(0)
        setFilters({ staffRole: staffRoles.find(value => value === role), businessStatus: staffStatuses.find(value => value === status), assignedZone: zone.trim() || undefined })
      }}>
        <label>Business role<select value={role} onChange={event => setRole(event.target.value)}><option value="">All roles</option>{staffRoles.map(value => <option key={value}>{value}</option>)}</select></label>
        <label>Business status<select value={status} onChange={event => setStatus(event.target.value)}><option value="">All statuses</option>{staffStatuses.map(value => <option key={value}>{value}</option>)}</select></label>
        <label>Assigned zone (exact match)<input value={zone} maxLength={100} onChange={event => setZone(event.target.value)} /></label>
        <Button variant="primary" type="submit" disabled={query.isFetching}>Apply filters</Button>
      </form>
      <p className="muted">Staff summaries identify employees by staff ID. Names and identity synchronization state are not included; open details for recorded reconciliation outcomes.</p>
      {query.isFetching && <p role="status">Loading staff…</p>}
      {query.isError && <ReadError error={query.error} busy={query.isFetching} retry={() => { void query.refetch() }} />}
      {query.data && !query.isError && <>
        {!query.data.content.length ? <p role="status">No staff match these filters on this page.</p> : <div className="admin-management-scroll" tabIndex={0} role="region" aria-label="Scrollable staff table"><table>
          <caption>Staff business profiles</caption><thead><tr>{['Staff ID', 'Profile ID', 'Role', 'Department', 'Zone', 'Business status', 'Updated', 'Details'].map(label => <th key={label} scope="col">{label}</th>)}</tr></thead>
          <tbody>{query.data.content.map(staff => <tr key={staff.profileId}><td>{staff.staffId}</td><td>{staff.profileId}</td><td>{staff.role}</td><td>{staff.department}</td><td>{staff.assignedZone ?? 'Not assigned'}</td><td><Badge value={staff.businessStatus} /></td><td><time dateTime={staff.updatedAt}>{staff.updatedAt}</time></td><td><Link to={`/admin/staff/${staff.profileId}`}>View {staff.staffId}</Link></td></tr>)}</tbody>
        </table></div>}
        <Pagination data={query.data} busy={query.isFetching} change={setPage} />
      </>}
    </section>
    <ProvisionForm api={api} scope={scope} />
  </>
}
const emptyProvision: ProvisionStaff = { keycloakSub: '', staffId: '', role: 'COURIER', department: '', assignedZone: null, accountProfileId: null }
function ProvisionForm({ api, scope }: { api: AdminManagementApi; scope: AdminScope }) {
  const [value, setValue] = useState<ProvisionStaff>(emptyProvision), [confirm, setConfirm] = useState<ProvisionStaff | null>(null), [problem, setProblem] = useState<string | null>(null)
  const command = useAdminCommand(scope), navigate = useNavigate(), formId = useId()
  return <section className="card" aria-labelledby={`${formId}-heading`}>
    <h2 id={`${formId}-heading`}>Provision staff business profile</h2>
    <p>Use an existing identity verified through the approved identity process. Accounts verifies the subject and reconciles the selected approved role. This form does not create a login or handle credentials.</p>
    <form onSubmit={event => { event.preventDefault(); const error = provisionProblem(value); setProblem(error); if (!error && !command.pending) setConfirm({ ...value }) }}>
      <fieldset disabled={command.pending || confirm !== null} className="admin-management-filters"><legend>Staff binding</legend>
        {([{ name: 'keycloakSub', label: 'Existing identity subject', max: 255 }, { name: 'staffId', label: 'Staff ID', max: 50 }, { name: 'department', label: 'Department', max: 100 }, { name: 'assignedZone', label: 'Assigned zone (optional)', max: 100 }, { name: 'accountProfileId', label: 'Account profile ID (optional UUID)', max: 36 }] as const).map(field => <label key={field.name}>{field.label}<input required={['keycloakSub', 'staffId', 'department'].includes(field.name)} maxLength={field.max} value={value[field.name] ?? ''} onChange={event => setValue({ ...value, [field.name]: ['assignedZone', 'accountProfileId'].includes(field.name) ? event.target.value || null : event.target.value })} /></label>)}
        <label>Approved role<select value={value.role} onChange={event => { const role = staffRoles.find(item => item === event.target.value); if (role) setValue({ ...value, role }) }}>{staffRoles.map(role => <option key={role}>{role}</option>)}</select></label>
        <Button variant="primary" type="submit" disabled={command.pending || confirm !== null}>Review provisioning</Button>
      </fieldset>
    </form>
    {problem && <p role="alert">{problem}</p>}
    {command.feedback && <p role={command.feedback.error ? 'alert' : 'status'}>{command.feedback.text}</p>}
    {command.feedback?.error && <p>Refresh the staff directory before resubmitting. If a profile exists, open its identity audit for recovery. If no profile appears, ask the Accounts owner to inspect reconciliation for the submitted identity.</p>}
    {confirm && <StaffConfirmation title="Provision this staff profile?" pending={command.pending} onCancel={() => setConfirm(null)} onConfirm={() => {
      void command.run(signal => api.provision(confirm, signal), id => { setValue(emptyProvision); navigate(`/admin/staff/${id}`) }, () => setConfirm(null))
    }}><p>Staff <strong>{confirm.staffId}</strong> · identity {confirm.keycloakSub}</p><p>Role: {confirm.role} · Department: {confirm.department} · Zone: {confirm.assignedZone ?? 'Not assigned'}</p>{confirm.accountProfileId && <p>Account binding: {confirm.accountProfileId}</p>}</StaffConfirmation>}
  </section>
}
function StaffDetails({ id, api, scope }: { id: string; api: AdminManagementApi; scope: AdminScope }) {
  const query = useAdminQuery(scope, ['detail', id], signal => api.detail(id, signal))
  return <section aria-label="Staff business details" aria-busy={query.isFetching}>
    <Link to="/admin/staff">Back to staff directory</Link>
    <p><Button variant="secondary" size="sm" disabled={query.isFetching} onClick={() => { void query.refetch() }}>Refresh profile</Button></p>
    {query.isFetching && <p role="status">Loading staff details…</p>}
    {query.isError && <ReadError error={query.error} busy={query.isFetching} retry={() => { void query.refetch() }} />}
    {query.data && !query.isError && <StaffRecord staff={query.data} refreshing={query.isFetching} api={api} scope={scope} />}
  </section>
}
function StaffRecord({ staff, refreshing, api, scope }: { staff: AdminStaff; refreshing: boolean; api: AdminManagementApi; scope: AdminScope }) {
  const audit = useAdminQuery(scope, ['audit', staff.keycloakSub, staff.role], signal => api.audit(staff, signal))
  const command = useAdminCommand(scope)
  const [confirm, setConfirm] = useState<StaffCommand | null>(null)
  const busy = refreshing || audit.isFetching || command.pending
  const actionsAvailable = !busy && !audit.isError
  return <div className="card">
    <h2>{staff.staffId}</h2>
    <dl className="admin-management-fields"><dt>Staff profile ID</dt><dd>{staff.profileId}</dd><dt>Identity subject</dt><dd>{staff.keycloakSub}</dd><dt>Account profile</dt><dd>{staff.accountProfileId ?? 'Not linked'}</dd><dt>Role</dt><dd>{staff.role}</dd><dt>Department</dt><dd>{staff.department}</dd><dt>Assigned zone</dt><dd>{staff.assignedZone ?? 'Not assigned'}</dd><dt>Business status</dt><dd><Badge value={staff.businessStatus} /></dd><dt>Created</dt><dd>{staff.createdAt}</dd><dt>Updated</dt><dd>{staff.updatedAt}</dd></dl>
    <p>Business status and identity reconciliation are separate. A business profile alone does not confirm current identity synchronization.</p>
    <div className="admin-management-toolbar"><Button variant="secondary" size="sm" disabled={!actionsAvailable || !canSuspend(staff)} onClick={() => setConfirm({ action: 'suspend', staff })}>Suspend staff</Button><Button variant="secondary" size="sm" disabled={!actionsAvailable} onClick={() => setConfirm({ action: 'sync', staff })}>Synchronize identity role</Button></div>
    <p className="muted">Activation and deactivation are awaiting contract-owner approval.</p>
    {command.feedback && <p role={command.feedback.error ? 'alert' : 'status'}>{command.feedback.text}</p>}
    <div className="admin-management-toolbar"><h3>Identity reconciliation audit</h3><Button variant="secondary" size="sm" disabled={busy} onClick={() => { void audit.refetch() }}>Refresh audit</Button></div>
    {audit.isFetching && <p role="status">Loading identity audit…</p>}
    {audit.isError && <ReadError error={audit.error} busy={busy} retry={() => { void audit.refetch() }} />}
    {audit.data && !audit.isError && <>
      {audit.data.length === 0 ? <p>No staff reconciliation records returned. Identity synchronization is unknown.</p> : <>
        <p>Latest recorded identity outcome: <Badge value={audit.data[0]!.outcome} /></p>
        <ol className="admin-management-audit">{audit.data.map(entry => <li key={entry.id}>
          <p>{entry.action} · <Badge value={entry.outcome} /> · <time dateTime={entry.updatedAt}>{entry.updatedAt}</time></p><p>Audit ID: {entry.id} · Actor: {entry.initiatedBy} · Attempts: {entry.attemptCount}</p>
          {(entry.outcome === 'FAILED' || entry.outcome === 'DENIED') && <p>The identity operation did not complete successfully. Review the business profile before recovery.</p>}
          {entry.outcome === 'PENDING_RETRY' && <p>Identity reconciliation is pending. Business access must not be assumed active.</p>}
          {canRecover(entry) && <Button variant="secondary" size="sm" disabled={!actionsAvailable} onClick={() => setConfirm({ action: 'retry', staff, auditId: entry.id })}>Recover audit {entry.id}</Button>}
        </li>)}</ol>
      </>}
    </>}
    {confirm && <StaffConfirmation title={confirm.action === 'suspend' ? 'Suspend this staff member?' : confirm.action === 'sync' ? 'Synchronize this staff identity?' : 'Recover this reconciliation?'} pending={command.pending} onCancel={() => setConfirm(null)} onConfirm={() => {
      if (!actionsAvailable || (confirm.action === 'suspend' && !canSuspend(staff))) return
      const value = { ...confirm, staff }
      void command.run(signal => api.command(value, signal), undefined, () => setConfirm(null))
    }}><p>Staff <strong>{staff.staffId}</strong> · profile {staff.profileId} · identity {staff.keycloakSub}</p><p>Role: {staff.role} · Current business status: {staff.businessStatus}</p><p>{confirm.action === 'suspend' ? 'Suspend business access and revoke the operational identity role.' : 'Re-read the current Accounts business state and reconcile the identity role. This does not activate an inactive profile.'}</p>{confirm.action === 'retry' && <p>Audit: {confirm.auditId}</p>}</StaffConfirmation>}
  </div>
}
