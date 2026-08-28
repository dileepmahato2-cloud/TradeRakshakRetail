import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';

interface BrokerMeta {
  id: string;
  name: string;
  description: string;
  required_fields: string[];
  oauth_url: string | null;
  requires_daily_token: boolean;
  docs_url: string;
}

interface MyBroker {
  id: number;
  broker_name: string;
  broker_label: string;
  client_id: string;
  api_key: string;
  api_secret: string;
  access_token?: string | null;
  is_active: number;
  is_verified: number;
  read_only: number;
  last_verified: string;
  verification_msg: string;
  token_expiry: string;
  auth_expired?: boolean;
  requires_reauth?: boolean;
  auth_status?: string;
  broker_meta: BrokerMeta;
  created_at?: string;
  updated_at?: string;
}

interface BrokerForm {
  brokerName: string;
  clientId: string;
  apiKey: string;
  apiSecret: string;
  accessToken: string;
  totpSecret: string;
  dhanPin: string;
  redirectUri: string;
  readOnly: boolean;
}

@Component({
  selector: 'app-broker',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styles: [`
    :host{display:block}.page{min-height:100vh;background:#071122;color:#e5eefb;padding:22px}
    .hero{background:linear-gradient(120deg,#102447,#0d2e37);border:1px solid #284466;border-top:3px solid #16d6a4;border-radius:8px;padding:22px 24px;margin-bottom:16px}
    .eyebrow{color:#5ff5cd;font-size:11px;font-weight:800;letter-spacing:.08em;text-transform:uppercase}
    h1{color:#fff;font-size:28px;font-weight:800;margin:5px 0 8px}h2{color:#fff;font-size:17px;font-weight:800;margin:0}
    .subtle{color:#9eb8dd;font-size:13px;line-height:1.45}.stats,.summary-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin-top:16px}
    .stat,.summary-card{background:rgba(15,23,42,.72);border:1px solid #2a4566;border-radius:7px;padding:12px;min-height:62px}
    .label{color:#9eb8dd;font-size:11px;font-weight:800}.value{color:#fff;font-size:16px;font-weight:800;overflow-wrap:anywhere}
    .section{background:#101b2d;border:1px solid #2b3d58;border-top:2px solid #2563eb;border-radius:7px;padding:18px;margin-bottom:16px}
    .section-header{display:flex;align-items:center;justify-content:space-between;gap:12px;border-bottom:1px solid #2b3d58;padding-bottom:12px;margin-bottom:16px}
    .workspace{display:flex;flex-direction:column;gap:14px}.broker-list{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px}
    .broker-row{width:100%;text-align:left;cursor:pointer;background:#0b1524;color:#dbeafe;border:1px solid #2b3d58;border-radius:7px;padding:12px}
    .broker-row:hover{border-color:#3b82f6;background:#0e1b2f}.broker-row.selected{border-color:#16d6a4;background:#0e2632;box-shadow:inset 3px 0 0 #16d6a4}
    .top{display:flex;align-items:center;justify-content:space-between;gap:10px}.name{color:#fff;font-size:13px;font-weight:800}.desc{color:#8ca3c4;font-size:11px;line-height:1.35;margin-top:6px}
    .chips{display:flex;flex-wrap:wrap;gap:5px;margin-top:8px}.chip{border:1px solid #2d4b72;color:#9dc6ff;background:#081326;border-radius:999px;padding:3px 7px;font-size:10px;font-weight:700}
    .ok{border-color:#127a54;color:#5ff5cd;background:#0b2a26}.warn{border-color:#9b6b10;color:#f8d26a;background:#2d2109}.danger{border-color:#a53b45;color:#ff9292;background:#311015}
    .panel-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.field{display:flex;flex-direction:column;gap:5px}.full{grid-column:1/-1}
    .input{background:#071122;border:1px solid #314761;border-radius:6px;color:#fff;padding:10px 11px;font-size:13px;width:100%;min-height:40px}.input:focus{outline:none;border-color:#3b82f6}
    .hint{color:#7187a8;font-size:10px;line-height:1.35}.notice{display:flex;gap:9px;padding:11px 12px;border-radius:7px;font-size:12px;line-height:1.45;border:1px solid #2d4b72;background:#0b1730;color:#b9d5ff}
    .notice.success{border-color:#15803d;background:#0d2b1d;color:#86efac}.actions{display:flex;justify-content:flex-end;align-items:center;gap:8px;flex-wrap:wrap;margin-top:14px}
    button{font-family:inherit}.btn{border:1px solid #334865;border-radius:7px;padding:9px 14px;font-size:12px;font-weight:800;cursor:pointer;color:#e5eefb;background:#1b2940}.btn:disabled{opacity:.55;cursor:not-allowed}
    .primary{background:#2563eb;border-color:#2563eb;color:#fff}.green{background:#0f7a52;border-color:#10b981;color:#fff}.red{background:#b91c1c;border-color:#ef4444;color:#fff}.ghost{background:#0b1524}
    .table-wrap{overflow:auto;border:1px solid #2b3d58;border-radius:7px}table{width:100%;border-collapse:collapse;min-width:880px}th,td{padding:10px 12px;border-bottom:1px solid #22344d;text-align:left;font-size:12px;white-space:nowrap}th{color:#c8d8ef;background:#142034;font-size:11px;text-transform:uppercase}
    .spin{width:13px;height:13px;display:inline-block;border:2px solid #ffffff59;border-top-color:#fff;border-radius:50%;animation:spin .7s linear infinite;vertical-align:-2px}@keyframes spin{to{transform:rotate(360deg)}}
    @media(max-width:1100px){.stats,.summary-grid,.broker-list{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:720px){.page{padding:14px}.stats,.summary-grid,.panel-grid,.broker-list{grid-template-columns:1fr}}
  `],
  template: `
<div class="page">
  <section class="hero">
    <div class="eyebrow">Broker Auth</div>
    <h1>Broker Connect</h1>
    <p class="subtle">
      Click a broker name, enter the authentication details requested by that broker, then Submit to connect.
      Keep Read-Only mode enabled until you are ready for real orders.
    </p>

    <div class="stats">
      <div class="stat">
        <div class="label">Supported Brokers</div>
        <div class="value">{{ supportedBrokers.length }}</div>
      </div>
      <div class="stat">
        <div class="label">Saved Connections</div>
        <div class="value">{{ myBrokers.length }}</div>
      </div>
      <div class="stat">
        <div class="label">Verified</div>
        <div class="value">{{ verifiedCount }}</div>
      </div>
      <div class="stat">
        <div class="label">Selected Broker</div>
        <div class="value">{{ selectedBrokerMeta?.name || '-' }}</div>
      </div>
    </div>
  </section>

  <div class="notice warn" style="margin-bottom:16px;">
    <span>!</span>
    <span>
      Broker connection can enable real money trading. TradeRakshak is not a SEBI registered advisor.
      Use Read-Only mode for data-only access unless you fully understand the risk.
    </span>
  </div>
  @if (pageError() && !selectedBrokerMeta) {
    <div class="notice danger" style="margin-bottom:16px;"><span>x</span><span>{{ pageError() }}</span></div>
  }
  @if (pageSuccess() && !selectedBrokerMeta) {
    <div class="notice success" style="margin-bottom:16px;"><span>ok</span><span>{{ pageSuccess() }}</span></div>
  }

  <section class="section">
    <div class="section-header">
      <div>
        <h2>Step 1 - Select Broker Name</h2>
        <p class="subtle">Broker names are displayed first. After you click one broker, its required authentication form opens here.</p>
      </div>
      <button class="btn ghost" (click)="reload()">Refresh</button>
    </div>

    <div class="workspace">
      <div class="broker-list">
        @for (broker of supportedBrokers; track broker.id) {
          @let connected = connectionFor(broker.id);
          <button
            type="button"
            class="broker-row"
            [class.selected]="selectedBrokerId === broker.id"
            (click)="selectBroker(broker)">
            <div class="top">
              <div>
                <div class="name">{{ broker.name }}</div>
                <div class="chips">
                  @if (connected) {
                    <span class="chip ok">Saved</span>
                    <span [class]="authStatusClass(connected)">
                      {{ authStatusLabel(connected) }}
                    </span>
                  } @else {
                    <span class="chip">Not Connected</span>
                  }
                  @if (broker.requires_daily_token) {
                    <span class="chip warn">Daily Token</span>
                  }
                </div>
              </div>
            </div>
            <div class="desc">{{ broker.description }}</div>
            <div class="chips">
              @for (field of broker.required_fields; track field) {
                <span class="chip">{{ fieldLabel(field) }}</span>
              }
            </div>
          </button>
        }
      </div>

      <div>
        @if (selectedBrokerMeta) {
          <div style="display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-bottom:12px;">
            <div>
              <div class="eyebrow">Step 2 - Required Authentication Details</div>
              <h2>{{ selectedBrokerMeta.name }}</h2>
            </div>
            <button class="btn ghost" type="button" (click)="closeBrokerForm()">Choose Another Broker</button>
          </div>
          <div class="notice" style="margin-bottom:12px;">
            <span>i</span>
            <span>
              {{ selectedBrokerMeta.description }}
              @if (selectedBrokerMeta.docs_url) {
                <a [href]="selectedBrokerMeta.docs_url" target="_blank" style="color:#93c5fd;font-weight:800;margin-left:6px;">API docs</a>
              }
            </span>
          </div>

          <div class="panel-grid">
            <div class="field">
              <label class="label">Client ID / User Name *</label>
              <input class="input" [(ngModel)]="form.clientId" (ngModelChange)="autoSaveField()" [placeholder]="clientIdPlaceholder()" />
              <div class="hint">
                Enter the login ID, client code, or user name issued by the selected broker.
                @if (selectedConnection && autoSaveLabel()) {
                  <span [style.color]="autoSaveState === 'error' ? '#ef4444' : '#22c55e'"> · {{ autoSaveLabel() }}</span>
                }
              </div>
            </div>

            @if (needsField('api_key')) {
              <div class="field">
                <label class="label">API Key *</label>
                <input class="input" [(ngModel)]="form.apiKey" placeholder="Enter API key" />
                <div class="hint">Available from the broker developer portal or API settings page.</div>
              </div>
            }

            @if (needsField('api_secret')) {
              <div class="field">
                <label class="label">API Secret {{ selectedConnection ? '(leave blank to keep saved)' : '*' }}</label>
                <input class="input" [(ngModel)]="form.apiSecret" [type]="showSecret() ? 'text' : 'password'" placeholder="Enter API secret" />
                <div class="actions" style="justify-content:flex-start;margin-top:0;">
                  <button type="button" class="btn ghost" style="padding:6px 10px;" (click)="showSecret.set(!showSecret())">
                    {{ showSecret() ? 'Hide' : 'Show' }}
                  </button>
                </div>
              </div>
            }

            @if (showAccessTokenField()) {
              <div class="field">
                <label class="label">Access Token {{ selectedConnection?.access_token ? '(leave blank to keep saved)' : '*' }}</label>
                <input class="input" [(ngModel)]="form.accessToken" [type]="showToken() ? 'text' : 'password'" placeholder="Enter access token" />
                <div class="actions" style="justify-content:flex-start;margin-top:0;">
                  <button type="button" class="btn ghost" style="padding:6px 10px;" (click)="showToken.set(!showToken())">
                    {{ showToken() ? 'Hide' : 'Show' }}
                  </button>
                </div>
              </div>
            }

            @if (isDhanSelected()) {
              <div class="field">
                <label class="label">Dhan PIN *</label>
                <input class="input" [(ngModel)]="form.dhanPin" (ngModelChange)="autoSaveField()" [type]="showSecret() ? 'text' : 'password'" placeholder="Enter Dhan PIN" />
                <div class="hint" style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
                  <label style="display:flex; align-items:center; gap:5px; cursor:pointer;">
                    <input type="checkbox" [(ngModel)]="rememberPin" (ngModelChange)="autoSaveField()" />
                    Remember this PIN
                  </label>
                  <span>{{ rememberPin ? 'Stored encrypted so you do not retype it each time.' : 'Used once to generate the token, then discarded.' }}</span>
                  @if (selectedConnection && autoSaveLabel()) {
                    <span [style.color]="autoSaveState === 'error' ? '#ef4444' : '#22c55e'">· {{ autoSaveLabel() }}</span>
                  }
                </div>
              </div>
            }

            @if (needsField('totp_secret')) {
              <div class="field">
                <label class="label">{{ isDhanSelected() ? 'Current TOTP' : 'TOTP Secret' }} {{ totpIsRequired() && !selectedConnection ? '*' : '(optional)' }}</label>
                <input class="input" [(ngModel)]="form.totpSecret" [type]="showTotp() ? 'text' : 'password'" [placeholder]="isDhanSelected() ? 'Enter current Dhan TOTP' : 'Enter TOTP secret'" />
                <div class="hint">{{ isDhanSelected() ? 'Enter the current code from your authenticator app.' : 'Used only where broker authentication needs automatic two-factor code generation.' }}</div>
                <div class="actions" style="justify-content:flex-start;margin-top:0;">
                  <button type="button" class="btn ghost" style="padding:6px 10px;" (click)="showTotp.set(!showTotp())">
                    {{ showTotp() ? 'Hide' : 'Show' }}
                  </button>
                </div>
              </div>
            }

            @if (selectedBrokerMeta.oauth_url) {
              <div class="field full">
                <label class="label">Redirect URI</label>
                <input class="input" [(ngModel)]="form.redirectUri" placeholder="Optional redirect URL configured in broker app" />
                <div class="hint">Use the same redirect URI that is registered in your broker application settings.</div>
              </div>
            }

            <div class="field full">
              <label class="label">Trading Permission</label>
              <select class="input" [(ngModel)]="form.readOnly">
                <option [ngValue]="true">Read-Only: data access only</option>
                <option [ngValue]="false">Trading Enabled: can place real orders</option>
              </select>
            </div>
          </div>

          @if (!form.readOnly) {
            <div class="notice danger" style="margin-top:12px;">
              <span>!</span>
              <span>Trading Enabled can send real buy and sell orders to the broker account. Real money can be used.</span>
            </div>
          }

          @if (pageError()) {
            <div class="notice danger" style="margin-top:12px;"><span>x</span><span>{{ pageError() }}</span></div>
          }
          @if (pageSuccess()) {
            <div class="notice success" style="margin-top:12px;"><span>ok</span><span>{{ pageSuccess() }}</span></div>
          }

          <div class="summary-grid">
            <div class="summary-card">
              <div class="label">Mode</div>
              <div class="value">{{ form.readOnly ? 'Read-Only' : 'Trading Enabled' }}</div>
            </div>
            <div class="summary-card">
              <div class="label">Client ID</div>
              <div class="value">{{ form.clientId || '-' }}</div>
            </div>
            <div class="summary-card">
              <div class="label">Connection</div>
              <div class="value">{{ selectedConnection ? 'Saved' : 'New' }}</div>
            </div>
            <div class="summary-card">
              <div class="label">Verification</div>
              <div class="value">{{ selectedConnection?.is_verified ? 'Verified' : 'Pending' }}</div>
            </div>
          </div>

          @if (selectedConnection?.verification_msg) {
            <div class="notice" style="margin-top:12px;">
              <span>i</span>
              <span>{{ selectedConnection?.verification_msg }}</span>
            </div>
          }
          @if (selectedConnection && needsReauth(selectedConnection)) {
            <div class="notice danger" style="margin-top:12px;">
              <span>!</span>
              <span>
                Broker authentication has expired or needs verification. Re-authenticate before using broker data, Stock Tracker live fetch, or real trading.
                @if (isDhanSelected()) {
                  Enter Dhan PIN and current TOTP, then click Re-authenticate Dhan.
                } @else {
                  Enter a fresh access token/session token, then click Update & Connect.
                }
              </span>
            </div>
          }

          <div class="actions">
            <button class="btn ghost" type="button" (click)="resetSelectedForm()">Clear</button>
            @if (selectedConnection) {
              <button class="btn green" type="button" (click)="verifyBroker(selectedConnection)" [disabled]="verifyingId() === selectedConnection.id">
                @if (verifyingId() === selectedConnection.id) { <span class="spin"></span> Verifying }
                @else { {{ needsReauth(selectedConnection) ? 'Re-authenticate' : 'Verify Connection' }} }
              </button>
            }
            <button class="btn primary" type="button" (click)="saveBroker()" [disabled]="saving()">
              @if (saving()) { <span class="spin"></span> Connecting }
              @else { {{ connectButtonLabel() }} }
            </button>
          </div>
        } @else {
          <div class="notice">Select any broker name from the broker list to enter Client ID, API Key, API Secret, Access Token, TOTP Secret or Redirect URI as applicable.</div>
        }
      </div>
    </div>
  </section>

  <section class="section">
    <div class="section-header">
      <div>
        <h2>Saved Broker Connections</h2>
        <p class="subtle">These connections are available to the rest of the application after verification.</p>
      </div>
    </div>

    @if (myBrokers.length === 0) {
      <div class="notice">No broker connections saved yet.</div>
    } @else {
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>#</th>
              <th>Broker Name</th>
              <th>Client ID</th>
              <th>Mode</th>
              <th>Status</th>
              <th>Last Verified</th>
              <th>Message</th>
              <th>Action</th>
            </tr>
          </thead>
          <tbody>
            @for (broker of myBrokers; track broker.id; let i = $index) {
              <tr>
                <td>{{ i + 1 }}</td>
                <td><strong>{{ broker.broker_label }}</strong></td>
                <td>{{ broker.client_id || '-' }}</td>
                <td>
                  <span [class]="broker.read_only ? 'chip ok' : 'chip danger'">
                    {{ broker.read_only ? 'Read-Only' : 'Trading Enabled' }}
                  </span>
                </td>
                <td>
                  <span [class]="broker.is_active ? 'chip ok' : 'chip danger'">{{ broker.is_active ? 'Active' : 'Disabled' }}</span>
                  <span [class]="authStatusClass(broker)" style="margin-left:5px;">{{ authStatusLabel(broker) }}</span>
                </td>
                <td>{{ broker.last_verified || '-' }}</td>
                <td>{{ broker.verification_msg || '-' }}</td>
                <td>
                  <div style="display:flex;gap:6px;flex-wrap:wrap;">
                    <button class="btn ghost" style="padding:6px 9px;" (click)="editBroker(broker)">Edit</button>
                    <button class="btn green" style="padding:6px 9px;" (click)="verifyBroker(broker)" [disabled]="verifyingId() === broker.id">
                      {{ needsReauth(broker) ? 'Re-authenticate' : 'Verify' }}
                    </button>
                    <button class="btn ghost" style="padding:6px 9px;" (click)="toggleActive(broker)">{{ broker.is_active ? 'Disable' : 'Enable' }}</button>
                    <button class="btn ghost" style="padding:6px 9px;" (click)="toggleReadOnly(broker)">{{ broker.read_only ? 'Allow Trade' : 'Read-Only' }}</button>
                    <button class="btn red" style="padding:6px 9px;" (click)="deleteBroker(broker)">Delete</button>
                  </div>
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
  </section>
</div>
  `
})
export class BrokerComponent implements OnInit {
  saving = signal(false);
  verifyingId = signal<number | null>(null);
  showSecret = signal(false);
  showToken = signal(false);
  showTotp = signal(false);
  pageError = signal('');
  pageSuccess = signal('');

  myBrokers: MyBroker[] = [];
  supportedBrokers: BrokerMeta[] = [];
  selectedBrokerId = '';

  form: BrokerForm = this.emptyForm();

  constructor(private api: ApiService) {}

  ngOnInit(): void {
    this.loadSupportedBrokers();
    this.loadMyBrokers();
  }

  get selectedBrokerMeta(): BrokerMeta | undefined {
    return this.supportedBrokers.find(b => b.id === this.selectedBrokerId);
  }

  get selectedConnection(): MyBroker | undefined {
    return this.connectionFor(this.selectedBrokerId);
  }

  get verifiedCount(): number {
    return this.myBrokers.filter(b => Number(b.is_verified) === 1 && !this.isAuthExpired(b)).length;
  }

  reload(): void {
    this.loadSupportedBrokers();
    this.loadMyBrokers();
    this.setSuccess('Broker details refreshed.');
  }

  loadSupportedBrokers(): void {
    this.api.get<any>('/broker/list').subscribe({
      next: r => {
        const rows = r.data || [];
        this.supportedBrokers = rows.length ? rows : this.fallbackBrokerCatalog();
        if (!rows.length) {
          this.setError('Broker master list was not returned by the server. Built-in broker list is shown.');
        }
        this.ensureSelection();
      },
      error: () => {
        this.supportedBrokers = this.fallbackBrokerCatalog();
        this.ensureSelection();
        this.setError('Broker master list could not be loaded from the server. Built-in broker list is shown.');
      }
    });
  }

  loadMyBrokers(): void {
    this.api.get<any>('/broker/my').subscribe({
      next: r => {
        this.myBrokers = r.data || [];
        this.ensureSelection();
        if (this.selectedBrokerId) {
          this.populateForm(this.selectedBrokerId);
        }
      },
      error: () => this.setError('Could not load your saved broker connections.')
    });
  }

  selectBroker(broker: BrokerMeta): void {
    this.selectedBrokerId = broker.id;
    this.populateForm(broker.id);
    this.clearMessage();
  }

  editBroker(broker: MyBroker): void {
    this.selectedBrokerId = broker.broker_name;
    this.populateForm(broker.broker_name);
    this.setSuccess(`${broker.broker_label} is ready to edit.`);
  }

  /** Remembering the PIN is opt-in: it is stored encrypted, same as the other secrets. */
  rememberPin = true;
  autoSaveState: 'idle' | 'saving' | 'saved' | 'error' = 'idle';
  private autoSaveTimer: any = null;

  /**
   * Saves Client ID and PIN shortly after typing stops, so they survive a reload without
   * having to press Save. Only fires for an already-saved connection — creating a brand new
   * one still goes through Save so the full form is validated first.
   */
  autoSaveField(): void {
    if (!this.selectedConnection) return;          // new connections use the Save button
    if (!this.form.brokerName) return;
    if (this.autoSaveTimer) clearTimeout(this.autoSaveTimer);
    this.autoSaveTimer = setTimeout(() => this.runAutoSave(), 1200);
  }

  private runAutoSave(): void {
    if (this.saving()) return;
    this.autoSaveState = 'saving';
    this.api.post<any>('/broker/save', {
      brokerName: this.form.brokerName,
      clientId: this.form.clientId,
      apiKey: this.form.apiKey,
      apiSecret: '',
      accessToken: '',
      totpSecret: '',
      dhanPin: this.rememberPin ? (this.form.dhanPin || '') : '',
      redirectUri: this.form.redirectUri,
      readOnly: this.form.readOnly
    }).subscribe({
      next: r => {
        this.autoSaveState = r.success === false ? 'error' : 'saved';
        if (this.autoSaveState === 'saved') setTimeout(() => { this.autoSaveState = 'idle'; }, 2500);
      },
      error: () => { this.autoSaveState = 'error'; }
    });
  }

  autoSaveLabel(): string {
    switch (this.autoSaveState) {
      case 'saving': return 'Saving...';
      case 'saved':  return 'Saved';
      case 'error':  return 'Not saved - press Save';
      default:       return '';
    }
  }

  saveBroker(): void {
    if (this.saving()) return; // guard: ignore re-entrant clicks while a save is in flight
    const validation = this.validateForm();
    if (validation) {
      this.setError(validation);
      return;
    }

    this.saving.set(true);
    this.clearMessage();
    this.api.post<any>('/broker/save', {
      brokerName: this.form.brokerName,
      clientId: this.form.clientId,
      apiKey: this.form.apiKey,
      apiSecret: this.isDhanSelected() ? '' : this.form.apiSecret,
      accessToken: this.isDhanSelected() ? '' : this.form.accessToken,
      totpSecret: this.isDhanSelected() ? '' : this.form.totpSecret,
      dhanPin: this.rememberPin ? (this.form.dhanPin || '') : '',
      redirectUri: this.form.redirectUri,
      readOnly: this.form.readOnly
    }).subscribe({
      next: r => {
        if (r.success === false) {
          this.saving.set(false);
          this.setError(r.message || 'Broker credentials could not be saved.');
          return;
        }
        this.setSuccess(this.isDhanSelected() ? 'Dhan details saved. Generating token now.' : (r.message || 'Broker credentials saved. Connecting with broker API now.'));
        this.connectSavedBroker();
      },
      error: e => {
        this.saving.set(false);
        this.setError(this.apiError(e, 'Broker credentials could not be saved.'));
      }
    });
  }

  private connectSavedBroker(): void {
    this.api.get<any>('/broker/my').subscribe({
      next: r => {
        this.myBrokers = r.data || [];
        const broker = this.connectionFor(this.selectedBrokerId);
        if (!broker) {
          this.saving.set(false);
          this.setSuccess('Broker credentials saved. Connection record will appear after refresh.');
          return;
        }
        this.verifyingId.set(broker.id);
        if (broker.broker_name === 'dhan') {
          this.generateDhanToken(broker);
          return;
        }
        this.api.post<any>(`/broker/verify/${broker.id}`, {}).subscribe({
          next: verify => {
            this.saving.set(false);
            this.verifyingId.set(null);
            if (verify.success === false || verify.data?.verified === false) {
              this.setError(verify.message || `${broker.broker_label} API connection failed. Check credentials and token.`);
            } else {
              this.setSuccess(verify.message || `${broker.broker_label} connected successfully.`);
            }
            this.loadMyBrokers();
          },
          error: e => {
            this.saving.set(false);
            this.verifyingId.set(null);
            this.setError(e.error?.message || `${broker.broker_label} credentials saved, but API connection failed.`);
            this.loadMyBrokers();
          }
        });
      },
      error: () => {
        this.saving.set(false);
        this.setSuccess('Broker credentials saved. Refresh the broker page to verify the connection.');
      }
    });
  }

  private generateDhanToken(broker: MyBroker): void {
    this.api.post<any>(`/broker/dhan/generate-token/${broker.id}`, {
      pin: this.form.dhanPin,
      totp: this.form.totpSecret
    }).subscribe({
      next: r => {
        this.saving.set(false);
        this.verifyingId.set(null);
        if (r.success === false || r.data?.verified === false) {
          this.setError(r.message || 'Dhan could not connect. Please check Client ID, PIN, and TOTP.');
        } else {
          this.setSuccess('Dhan connected. Token generated automatically.');
          if (!this.rememberPin) this.form.dhanPin = '';
          this.form.totpSecret = '';
        }
        this.loadMyBrokers();
      },
      error: e => {
        this.saving.set(false);
        this.verifyingId.set(null);
        this.setError(this.apiError(e, 'Dhan could not connect. Please check Client ID, PIN, and TOTP.'));
        this.loadMyBrokers();
      }
    });
  }

  verifyBroker(broker: MyBroker): void {
    if (this.verifyingId() !== null || this.saving()) return; // guard: one verify/connect at a time
    if (this.selectedBrokerId !== broker.broker_name) {
      this.selectedBrokerId = broker.broker_name;
      this.populateForm(broker.broker_name);
    }
    if (broker.broker_name === 'dhan') {
      if (!this.form.dhanPin.trim() && !this.rememberPin) {
        this.setError('Enter Dhan PIN, then click Connect Dhan.');
        return;
      }
      if (!this.form.totpSecret.trim()) {
        this.setError('Enter current Dhan TOTP, then click Connect Dhan.');
        return;
      }
      this.verifyingId.set(broker.id);
      this.clearMessage();
      this.generateDhanToken(broker);
      return;
    }
    if (this.needsReauth(broker) && broker.broker_meta?.required_fields?.includes('access_token') && !this.form.accessToken.trim()) {
      this.setError(`Enter a fresh access token/session token for ${broker.broker_label}, then click Update & Connect.`);
      return;
    }
    this.verifyingId.set(broker.id);
    this.clearMessage();
    this.api.post<any>(`/broker/verify/${broker.id}`, {}).subscribe({
      next: r => {
        this.verifyingId.set(null);
        if (r.success === false || r.data?.verified === false || r.data?.requiresReauth) {
          this.setError(r.message || `${broker.broker_label} authentication needs renewal. Update the token and connect again.`);
        } else {
          this.setSuccess(r.message || `${broker.broker_label} connected successfully.`);
        }
        this.loadMyBrokers();
      },
      error: e => {
        this.verifyingId.set(null);
        this.setError(e.error?.message || `${broker.broker_label} verification failed.`);
      }
    });
  }

  /** Guards a double-click, which would otherwise toggle twice and land back where it started. */
  togglingId: number | null = null;

  toggleActive(broker: MyBroker): void {
    if (this.togglingId !== null) return;
    this.togglingId = broker.id;
    this.api.put<any>(`/broker/${broker.id}/toggle`, {}).subscribe({
      next: r => {
        this.togglingId = null;
        this.setSuccess(r.message || 'Broker status updated.');
        this.loadMyBrokers();
      },
      error: e => { this.togglingId = null; this.setError(e.error?.message || 'Broker status could not be changed.'); }
    });
  }

  toggleReadOnly(broker: MyBroker): void {
    if (Number(broker.read_only) === 1) {
      const confirmed = confirm(
        'This will allow real buy and sell orders through this broker. Real money can be used. Continue?'
      );
      if (!confirmed) return;
    }
    if (this.togglingId !== null) return;
    this.togglingId = broker.id;
    this.api.put<any>(`/broker/${broker.id}/readonly`, {}).subscribe({
      next: r => {
        this.togglingId = null;
        this.setSuccess(r.message || 'Broker trading permission updated.');
        this.loadMyBrokers();
      },
      error: e => { this.togglingId = null; this.setError(e.error?.message || 'Trading permission could not be changed.'); }
    });
  }

  deleteBroker(broker: MyBroker): void {
    if (!confirm(`Remove ${broker.broker_label} credentials?`)) return;
    this.api.delete<any>(`/broker/${broker.id}`).subscribe({
      next: r => {
        this.setSuccess(r.message || 'Broker connection deleted.');
        if (this.selectedBrokerId === broker.broker_name) {
          this.form = this.emptyFormFor(broker.broker_name);
        }
        this.loadMyBrokers();
      },
      error: e => this.setError(e.error?.message || 'Broker connection could not be deleted.')
    });
  }

  resetSelectedForm(): void {
    this.form = this.emptyFormFor(this.selectedBrokerId);
    this.clearMessage();
  }

  closeBrokerForm(): void {
    this.selectedBrokerId = '';
    this.form = this.emptyForm();
    this.clearMessage();
  }

  connectionFor(brokerId: string): MyBroker | undefined {
    return this.myBrokers.find(b => b.broker_name === brokerId);
  }

  needsField(field: string): boolean {
    return this.selectedBrokerMeta?.required_fields?.includes(field) ?? false;
  }

  isDhanSelected(): boolean {
    return this.selectedBrokerId === 'dhan';
  }

  showAccessTokenField(): boolean {
    return this.needsField('access_token') && !this.isDhanSelected();
  }

  connectButtonLabel(): string {
    if (this.isDhanSelected()) {
      if (this.selectedConnection && this.needsReauth(this.selectedConnection)) return 'Re-authenticate Dhan';
      return this.selectedConnection ? 'Update Dhan' : 'Connect Dhan';
    }
    return this.selectedConnection ? 'Update & Connect' : 'Submit & Connect';
  }

  isAuthExpired(broker: MyBroker | undefined): boolean {
    if (!broker) return false;
    if (broker.auth_expired === true) return true;
    const expiry = (broker.token_expiry || '').trim();
    if (!expiry) return false;
    const time = Date.parse(expiry.replace(' ', 'T'));
    return Number.isFinite(time) && time < Date.now();
  }

  needsReauth(broker: MyBroker | undefined): boolean {
    if (!broker) return false;
    return broker.requires_reauth === true || this.isAuthExpired(broker) || Number(broker.is_verified) !== 1;
  }

  authStatusLabel(broker: MyBroker): string {
    if (this.isAuthExpired(broker) || broker.auth_status === 'expired') return 'Authentication Expired';
    if (Number(broker.is_verified) === 1) return 'Verified';
    return 'Re-authenticate';
  }

  authStatusClass(broker: MyBroker): string {
    if (this.isAuthExpired(broker) || broker.auth_status === 'expired') return 'chip danger';
    if (Number(broker.is_verified) === 1) return 'chip ok';
    return 'chip warn';
  }

  totpIsRequired(): boolean {
    return this.selectedBrokerId === 'dhan' || this.selectedBrokerId === 'angel' || this.selectedBrokerId === 'shoonya';
  }

  fieldLabel(field: string): string {
    const labels: Record<string, string> = {
      client_id: 'Client ID',
      api_key: 'API Key',
      api_secret: 'API Secret',
      access_token: 'Access Token',
      totp_secret: 'TOTP Secret',
      dhan_pin: 'Dhan PIN'
    };
    return labels[field] || field;
  }

  clientIdPlaceholder(): string {
    const placeholders: Record<string, string> = {
      zerodha: 'Example: AB1234',
      upstox: 'Example: client12345',
      angel: 'Example: A123456',
      fyers: 'Example: XY12345',
      dhan: 'Example: 1000000001',
      shoonya: 'Example: FA12345',
      kotak: 'Example: 12345678',
      icici: 'Example: abc123'
    };
    return placeholders[this.selectedBrokerId] || 'Enter broker user name or client ID';
  }

  private fallbackBrokerCatalog(): BrokerMeta[] {
    return [
      {
        id: 'dhan',
        name: 'Dhan',
        description: 'Enter Client ID, PIN, and current TOTP. The app generates the Dhan token automatically.',
        required_fields: ['client_id', 'dhan_pin', 'totp_secret'],
        oauth_url: null,
        requires_daily_token: true,
        docs_url: 'https://dhanhq.co/docs/v2/'
      },
      {
        id: 'zerodha',
        name: 'Zerodha Kite',
        description: 'Zerodha Kite Connect session-token connection for profile, positions, and orders.',
        required_fields: ['client_id', 'api_key', 'api_secret', 'access_token'],
        oauth_url: 'https://kite.trade/connect/login',
        requires_daily_token: true,
        docs_url: 'https://kite.trade/docs/connect/v3/'
      },
      {
        id: 'upstox',
        name: 'Upstox',
        description: 'Upstox API connection using OAuth access token for profile, market data, and orders.',
        required_fields: ['client_id', 'api_key', 'api_secret', 'access_token'],
        oauth_url: 'https://api.upstox.com/v2/login/authorization/dialog',
        requires_daily_token: true,
        docs_url: 'https://upstox.com/developer/api-documentation/'
      },
      {
        id: 'angel',
        name: 'Angel One SmartAPI',
        description: 'Angel One SmartAPI connection using client code, API key, PIN/password, and TOTP.',
        required_fields: ['client_id', 'api_key', 'api_secret', 'totp_secret'],
        oauth_url: null,
        requires_daily_token: true,
        docs_url: 'https://smartapi.angelbroking.com/docs'
      },
      {
        id: 'fyers',
        name: 'Fyers',
        description: 'Fyers API connection using app ID/API key and OAuth access token.',
        required_fields: ['client_id', 'api_key', 'api_secret', 'access_token'],
        oauth_url: 'https://api-t1.fyers.in/api/v3/generate-authcode',
        requires_daily_token: true,
        docs_url: 'https://myapi.fyers.in/docsv3'
      },
      {
        id: 'shoonya',
        name: 'Shoonya',
        description: 'Shoonya Noren API connection using user ID and generated session token.',
        required_fields: ['client_id', 'api_key', 'api_secret', 'access_token'],
        oauth_url: null,
        requires_daily_token: true,
        docs_url: 'https://github.com/Shoonya-Dev/ShoonyaApi-py'
      },
      {
        id: 'kotak',
        name: 'Kotak Neo',
        description: 'Kotak Neo API connection using consumer details and access token.',
        required_fields: ['client_id', 'api_key', 'api_secret', 'access_token'],
        oauth_url: null,
        requires_daily_token: true,
        docs_url: 'https://napi.kotaksecurities.com/devportal/apis'
      },
      {
        id: 'icici',
        name: 'ICICI Direct Breeze',
        description: 'ICICI Breeze API connection using app key, secret key, and session token.',
        required_fields: ['client_id', 'api_key', 'api_secret', 'access_token'],
        oauth_url: 'https://api.icicidirect.com/apiuser/login',
        requires_daily_token: true,
        docs_url: 'https://api.icicidirect.com/breezeapi/documents/index.html'
      }
    ];
  }

  private ensureSelection(): void {
    if (this.selectedBrokerId && !this.supportedBrokers.some(b => b.id === this.selectedBrokerId)) {
      this.selectedBrokerId = '';
      this.form = this.emptyForm();
    }
  }

  private populateForm(brokerId: string): void {
    const saved = this.connectionFor(brokerId);
    if (saved) {
      this.form = {
        brokerName: saved.broker_name,
        clientId: saved.client_id || '',
        apiKey: saved.api_key || '',
        apiSecret: '',
        accessToken: '',
        totpSecret: '',
        dhanPin: '',
        redirectUri: '',
        readOnly: Number(saved.read_only) === 1
      };
      return;
    }
    this.form = this.emptyFormFor(brokerId);
  }

  private validateForm(): string {
    if (!this.selectedBrokerMeta || !this.form.brokerName) return 'Select a broker name first.';
    if (!this.form.clientId.trim()) return 'Client ID / User Name is required.';
    if (this.isDhanSelected()) {
      if (!this.form.dhanPin.trim() && !this.rememberPin) return 'Dhan PIN is required.';
      if (!this.form.totpSecret.trim()) return 'Current Dhan TOTP is required.';
      return '';
    }
    if (this.needsField('api_key') && !this.form.apiKey.trim()) return 'API Key is required.';
    if (this.needsField('api_secret') && !this.selectedConnection && !this.form.apiSecret.trim()) {
      return 'API Secret is required for this broker.';
    }
    if (this.needsField('access_token') && (!this.selectedConnection?.access_token || this.needsReauth(this.selectedConnection)) && !this.form.accessToken.trim()) {
      return 'Access Token is required for this broker.';
    }
    if (this.needsField('totp_secret') && this.totpIsRequired() && !this.selectedConnection && !this.form.totpSecret.trim()) {
      return 'TOTP Secret is required for this broker.';
    }
    return '';
  }

  private setError(message: string): void {
    this.pageSuccess.set('');
    this.pageError.set(message);
  }

  private setSuccess(message: string): void {
    this.pageError.set('');
    this.pageSuccess.set(message);
  }

  private clearMessage(): void {
    this.pageError.set('');
    this.pageSuccess.set('');
  }

  private emptyFormFor(brokerName: string): BrokerForm {
    return {
      ...this.emptyForm(),
      brokerName
    };
  }

  private emptyForm(): BrokerForm {
    return {
      brokerName: '',
      clientId: '',
      apiKey: '',
      apiSecret: '',
      accessToken: '',
      totpSecret: '',
      dhanPin: '',
      redirectUri: '',
      readOnly: true
    };
  }

  private apiError(error: any, fallback: string): string {
    const message = error?.error?.message || error?.message || '';
    if (message) return message;
    if (error?.status === 401 || error?.status === 403) {
      return 'Login session expired. Please log in again, then connect Dhan.';
    }
    if (error?.status === 0) {
      return 'Server connection failed. Please make sure TradeRakshak is running, then try again.';
    }
    return fallback;
  }
}
