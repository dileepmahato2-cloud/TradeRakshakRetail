import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-notification-settings',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
<div class="p-6 max-w-3xl mx-auto">
  <div class="flex items-center justify-between mb-6">
    <div>
      <h1 class="section-title">🔔 Notification Settings</h1>
      <p class="text-slate-400 text-sm">Configure how and where you receive alerts, trade notifications, and account messages.</p>
    </div>
    <button (click)="save()" [disabled]="saving()" class="btn-primary">
      @if (saving()) { Saving… } @else { 💾 Save All Settings }
    </button>
  </div>

  @if (success()) {
    <div class="bg-green-900/40 border border-green-700 text-green-300 rounded-lg px-4 py-3 mb-4 text-sm">✅ {{ success() }}</div>
  }
  @if (error()) {
    <div class="bg-red-900/40 border border-red-700 text-red-300 rounded-lg px-4 py-3 mb-4 text-sm">{{ error() }}</div>
  }

  @if (loading()) { <div class="text-center text-slate-400 py-12">Loading settings…</div> }

  @if (!loading() && form) {
    <!-- ── CHANNEL SETUP ──────────────────────────────────────────────── -->
    <div class="space-y-4 mb-6">

      <!-- In-App (always on) -->
      <div class="card border border-slate-700">
        <div class="flex items-start justify-between">
          <div class="flex items-center gap-3">
            <div class="w-10 h-10 rounded-xl bg-blue-900/40 flex items-center justify-center text-xl">📱</div>
            <div>
              <div class="font-semibold text-white">In-App Notifications</div>
              <div class="text-xs text-slate-400">Real-time alerts inside the platform dashboard.</div>
            </div>
          </div>
          <span class="badge badge-green text-xs">Always On</span>
        </div>
      </div>

      <!-- Email -->
      <div class="card border transition-all" [class]="form.emailEnabled ? 'border-blue-700/50' : 'border-slate-700'">
        <div class="flex items-start gap-4">
          <div class="w-10 h-10 rounded-xl bg-blue-900/40 flex items-center justify-center text-xl flex-shrink-0">📧</div>
          <div class="flex-1 min-w-0">
            <div class="flex items-center justify-between mb-3">
              <div>
                <div class="font-semibold text-white">Email</div>
                <div class="text-xs text-slate-400">Reliable delivery via SMTP. Recommended for all important alerts.</div>
              </div>
              <label class="flex items-center gap-2 cursor-pointer flex-shrink-0">
                <input type="checkbox" [(ngModel)]="form.emailEnabled" class="sr-only" />
                <div class="relative w-10 h-5 rounded-full transition-colors cursor-pointer"
                  [class]="form.emailEnabled ? 'bg-green-600' : 'bg-slate-600'"
                  (click)="form.emailEnabled = !form.emailEnabled">
                  <span class="absolute inset-y-0.5 w-4 h-4 bg-white rounded-full shadow transition-all"
                    [class]="form.emailEnabled ? 'left-5' : 'left-0.5'"></span>
                </div>
              </label>
            </div>
            @if (form.emailEnabled) {
              <div class="flex gap-2 items-end">
                <div class="flex-1">
                  <label class="label">Email Address</label>
                  <input [(ngModel)]="form.emailAddress" type="email" class="input text-sm"
                    placeholder="your@email.com" />
                </div>
                <button (click)="test('email')" [disabled]="testing('email')" class="btn-secondary text-sm flex-shrink-0 h-10">
                  @if (testing('email')) { Sending… } @else { 📧 Test }
                </button>
              </div>
              @if (testResults['email']) {
                <div class="text-xs mt-1" [class]="testResults['email'].sent ? 'text-green-400' : 'text-red-400'">
                  {{ testResults['email'].detail }}
                </div>
              }
            }
          </div>
        </div>
      </div>

      <!-- SMS -->
      <div class="card border transition-all" [class]="form.smsEnabled ? 'border-green-700/50' : 'border-slate-700'">
        <div class="flex items-start gap-4">
          <div class="w-10 h-10 rounded-xl bg-green-900/40 flex items-center justify-center text-xl flex-shrink-0">📱</div>
          <div class="flex-1 min-w-0">
            <div class="flex items-center justify-between mb-3">
              <div>
                <div class="font-semibold text-white">SMS</div>
                <div class="text-xs text-slate-400">Indian mobile numbers via MSG91. Instant delivery.</div>
              </div>
              <label class="flex items-center gap-2 cursor-pointer flex-shrink-0">
                <input type="checkbox" [(ngModel)]="form.smsEnabled" class="sr-only" />
                <div class="relative w-10 h-5 rounded-full transition-colors cursor-pointer"
                  [class]="form.smsEnabled ? 'bg-green-600' : 'bg-slate-600'"
                  (click)="form.smsEnabled = !form.smsEnabled">
                  <span class="absolute inset-y-0.5 w-4 h-4 bg-white rounded-full shadow transition-all"
                    [class]="form.smsEnabled ? 'left-5' : 'left-0.5'"></span>
                </div>
              </label>
            </div>
            @if (form.smsEnabled) {
              <div class="flex gap-2 items-end">
                <div class="flex-1">
                  <label class="label">Mobile Number (10 digits)</label>
                  <input [(ngModel)]="form.smsMobile" type="tel" maxlength="10" class="input text-sm"
                    placeholder="9876543210" />
                </div>
                <button (click)="test('sms')" [disabled]="testing('sms')" class="btn-secondary text-sm flex-shrink-0 h-10">
                  @if (testing('sms')) { Sending… } @else { 📱 Test }
                </button>
              </div>
              @if (testResults['sms']) {
                <div class="text-xs mt-1" [class]="testResults['sms'].sent ? 'text-green-400' : 'text-red-400'">
                  {{ testResults['sms'].detail }}
                </div>
              }
            }
          </div>
        </div>
      </div>

      <!-- Telegram -->
      <div class="card border transition-all" [class]="form.telegramEnabled ? 'border-sky-700/50' : 'border-slate-700'">
        <div class="flex items-start gap-4">
          <div class="w-10 h-10 rounded-xl bg-sky-900/40 flex items-center justify-center text-xl flex-shrink-0">✈️</div>
          <div class="flex-1 min-w-0">
            <div class="flex items-center justify-between mb-3">
              <div>
                <div class="font-semibold text-white">Telegram</div>
                <div class="text-xs text-slate-400">Instant rich messages via {{ telegramBotName }}.</div>
              </div>
              <label class="flex items-center gap-2 cursor-pointer flex-shrink-0">
                <input type="checkbox" [(ngModel)]="form.telegramEnabled" class="sr-only" />
                <div class="relative w-10 h-5 rounded-full transition-colors cursor-pointer"
                  [class]="form.telegramEnabled ? 'bg-green-600' : 'bg-slate-600'"
                  (click)="form.telegramEnabled = !form.telegramEnabled">
                  <span class="absolute inset-y-0.5 w-4 h-4 bg-white rounded-full shadow transition-all"
                    [class]="form.telegramEnabled ? 'left-5' : 'left-0.5'"></span>
                </div>
              </label>
            </div>
            @if (form.telegramEnabled) {
              <!-- Setup guide -->
              <div class="bg-sky-900/20 border border-sky-700/30 rounded-lg p-3 mb-3 text-xs text-sky-300">
                <div class="font-semibold mb-1">📋 How to get your Telegram Chat ID:</div>
                <ol class="list-decimal list-inside space-y-1 text-sky-200">
                  <li>Open Telegram → Search <strong>{{ telegramBotName }}</strong></li>
                  <li>Click <strong>Start</strong> or send <code class="bg-sky-900 px-1 rounded">/start</code></li>
                  <li>The bot replies with your <strong>Chat ID</strong> (a number)</li>
                  <li>Copy it and paste below</li>
                </ol>
              </div>
              <div class="flex gap-2 items-end">
                <div class="flex-1">
                  <label class="label">
                    Telegram Chat ID
                    @if (form.telegramVerified) { <span class="text-green-400 ml-1">✓ Verified</span> }
                  </label>
                  <input [(ngModel)]="form.telegramChatId" class="input text-sm font-mono"
                    placeholder="123456789" />
                </div>
                <button (click)="test('telegram')" [disabled]="testing('telegram')" class="btn-secondary text-sm flex-shrink-0 h-10">
                  @if (testing('telegram')) { Sending… } @else { ✈️ Test }
                </button>
              </div>
              @if (testResults['telegram']) {
                <div class="text-xs mt-1" [class]="testResults['telegram'].sent ? 'text-green-400' : 'text-red-400'">
                  {{ testResults['telegram'].detail }}
                </div>
              }
            }
          </div>
        </div>
      </div>

      <!-- WhatsApp -->
      <div class="card border transition-all" [class]="form.whatsappEnabled ? 'border-green-600/50' : 'border-slate-700'">
        <div class="flex items-start gap-4">
          <div class="w-10 h-10 rounded-xl bg-green-900/40 flex items-center justify-center text-2xl flex-shrink-0">💬</div>
          <div class="flex-1 min-w-0">
            <div class="flex items-center justify-between mb-3">
              <div>
                <div class="font-semibold text-white">WhatsApp</div>
                <div class="text-xs text-slate-400">Pre-approved templates via Twilio/Meta. Requires opt-in.</div>
              </div>
              <label class="flex items-center gap-2 cursor-pointer flex-shrink-0">
                <input type="checkbox" [(ngModel)]="form.whatsappEnabled" class="sr-only" />
                <div class="relative w-10 h-5 rounded-full transition-colors cursor-pointer"
                  [class]="form.whatsappEnabled ? 'bg-green-600' : 'bg-slate-600'"
                  (click)="form.whatsappEnabled = !form.whatsappEnabled">
                  <span class="absolute inset-y-0.5 w-4 h-4 bg-white rounded-full shadow transition-all"
                    [class]="form.whatsappEnabled ? 'left-5' : 'left-0.5'"></span>
                </div>
              </label>
            </div>
            @if (form.whatsappEnabled) {
              <div class="flex gap-2 items-end mb-3">
                <div class="flex-1">
                  <label class="label">WhatsApp Number (with country code)</label>
                  <input [(ngModel)]="form.whatsappNumber" type="tel" class="input text-sm"
                    placeholder="+919876543210" />
                </div>
                <button (click)="test('whatsapp')" [disabled]="testing('whatsapp')" class="btn-secondary text-sm flex-shrink-0 h-10">
                  @if (testing('whatsapp')) { Sending… } @else { 💬 Test }
                </button>
              </div>
              <label class="flex items-start gap-2 cursor-pointer">
                <input type="checkbox" [(ngModel)]="form.whatsappOptIn" class="mt-0.5 accent-green-500" />
                <span class="text-xs text-slate-400">
                  I consent to receive WhatsApp messages from TradeRakshak™ at the above number.
                  Messages include alerts, trade notifications, and account updates only.
                  <strong class="text-amber-400">Standard messaging charges may apply.</strong>
                </span>
              </label>
              @if (testResults['whatsapp']) {
                <div class="text-xs mt-1" [class]="testResults['whatsapp'].sent ? 'text-green-400' : 'text-red-400'">
                  {{ testResults['whatsapp'].detail }}
                </div>
              }
            }
          </div>
        </div>
      </div>
    </div>

    <!-- ── NOTIFICATION TYPE PREFERENCES ──────────────────────────────── -->
    <div class="card mb-6">
      <div class="card-header">📋 What to Notify Per Channel</div>
      <div class="overflow-x-auto">
        <table class="w-full text-sm">
          <thead>
            <tr class="text-slate-400 text-xs border-b border-slate-700">
              <th class="text-left pb-3 pr-4">Notification Type</th>
              <th class="text-center pb-3 px-3" title="In-App">📱 App</th>
              <th class="text-center pb-3 px-3" title="Email">📧 Email</th>
              <th class="text-center pb-3 px-3" title="SMS">📱 SMS</th>
              <th class="text-center pb-3 px-3" title="Telegram">✈️ TG</th>
              <th class="text-center pb-3 px-3" title="WhatsApp">💬 WA</th>
            </tr>
          </thead>
          <tbody>
            @for (row of notifRows; track row.key) {
              <tr class="border-b border-slate-700/40 hover:bg-slate-800/30">
                <td class="py-3 pr-4">
                  <div class="font-medium text-white">{{ row.label }}</div>
                  <div class="text-xs text-slate-500">{{ row.desc }}</div>
                </td>
                <td class="text-center py-3 px-3"><span class="text-green-400 text-xs">✓</span></td>
                <td class="text-center py-3 px-3">
                  <input type="checkbox" [(ngModel)]="form['notify' + row.key + 'Email']"
                    [disabled]="!form.emailEnabled" class="accent-blue-500" />
                </td>
                <td class="text-center py-3 px-3">
                  <input type="checkbox" [(ngModel)]="form['notify' + row.key + 'Sms']"
                    [disabled]="!form.smsEnabled" class="accent-green-500" />
                </td>
                <td class="text-center py-3 px-3">
                  <input type="checkbox" [(ngModel)]="form['notify' + row.key + 'Telegram']"
                    [disabled]="!form.telegramEnabled" class="accent-sky-500" />
                </td>
                <td class="text-center py-3 px-3">
                  <input type="checkbox" [(ngModel)]="form['notify' + row.key + 'Whatsapp']"
                    [disabled]="!form.whatsappEnabled || !form.whatsappOptIn" class="accent-green-500" />
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      <p class="text-xs text-slate-500 mt-3">
        Grayed-out checkboxes require the corresponding channel to be enabled first.
      </p>
    </div>

    <!-- ── QUIET HOURS ────────────────────────────────────────────────── -->
    <div class="card mb-6">
      <div class="flex items-center justify-between mb-3">
        <div>
          <div class="font-semibold text-white">🌙 Quiet Hours</div>
          <div class="text-xs text-slate-400">Suppress all notifications during these hours (except critical account alerts).</div>
        </div>
        <label class="flex items-center gap-2 cursor-pointer">
          <input type="checkbox" [(ngModel)]="form.quietHoursEnabled" class="accent-blue-500" />
          <span class="text-sm text-slate-300">Enable</span>
        </label>
      </div>
      @if (form.quietHoursEnabled) {
        <div class="flex gap-4">
          <div>
            <label class="label">From</label>
            <input [(ngModel)]="form.quietStartTime" type="time" class="input text-sm w-32" />
          </div>
          <div>
            <label class="label">To</label>
            <input [(ngModel)]="form.quietEndTime" type="time" class="input text-sm w-32" />
          </div>
          <div class="flex items-end pb-0.5">
            <span class="text-xs text-slate-400">(IST)</span>
          </div>
        </div>
      }
    </div>

    <!-- Save button -->
    <div class="flex justify-end">
      <button (click)="save()" [disabled]="saving()" class="btn-primary px-8">
        @if (saving()) { Saving… } @else { 💾 Save Notification Settings }
      </button>
    </div>
  }
</div>
  `
})
export class NotificationSettingsComponent implements OnInit {
  loading  = signal(true);
  saving   = signal(false);
  success  = signal('');
  error    = signal('');
  form: any = null;
  testResults: Record<string, any> = {};
  testingChannels = new Set<string>();
  telegramBotName = '@TradeRakshakBot';

  notifRows = [
    { key: 'Alerts',      label: '🚨 Trade Alerts',     desc: 'Scanner signals, breakout alerts, strategy triggers' },
    { key: 'Trades',      label: '📈 Trade Notifications', desc: 'Paper trade entries, exits, P&L updates' },
    { key: 'Account',     label: '🔐 Account & Security', desc: 'Login alerts, password changes, subscription updates' },
    { key: 'Competition', label: '🏆 Competitions',     desc: 'Registration confirmations, leaderboard updates, rewards' },
    { key: 'Lms',         label: '🎓 LMS & Learning',   desc: 'Course enrollments, quiz results, certificates' },
  ];

  constructor(private api: ApiService, public auth: AuthService) {}

  ngOnInit() {
    // Load platform config for bot name
    this.api.get<any>('/admin/features/platform-config').subscribe({
      next: r => {
        if (r.data?.telegram?.botUsername) this.telegramBotName = r.data.telegram.botUsername;
      }
    });
    // Load user settings
    this.api.get<any>('/admin/features/channels').subscribe({
      next: r => { this.form = { ...r.data }; this.loading.set(false); },
      error: () => {
        // Build defaults
        const u = this.auth.currentUser();
        this.form = {
          emailEnabled: true, emailAddress: u?.email || '',
          smsEnabled: false, smsMobile: '',
          telegramEnabled: false, telegramChatId: '',
          telegramBotName: '@TradeRakshakBot', telegramVerified: false,
          whatsappEnabled: false, whatsappNumber: '', whatsappOptIn: false,
          inAppEnabled: true,
          notifyAlertsEmail: true, notifyAlertsSms: false, notifyAlertsTelegram: false, notifyAlertsWhatsapp: false,
          notifyTradesEmail: true, notifyTradesSms: false, notifyTradesTelegram: false, notifyTradesWhatsapp: false,
          notifyAccountEmail: true, notifyAccountSms: true, notifyAccountTelegram: false, notifyAccountWhatsapp: false,
          notifyCompetitionEmail: true, notifyCompetitionSms: false,
          notifyLmsEmail: true, notifyLmsSms: false,
          quietHoursEnabled: false, quietStartTime: '22:00', quietEndTime: '07:00'
        };
        this.loading.set(false);
      }
    });
  }

  testing(channel: string) { return this.testingChannels.has(channel); }

  test(channel: string) {
    this.testingChannels.add(channel);
    this.testResults[channel] = null;
    this.api.post<any>(`/admin/features/channels/${channel}/test`, {}).subscribe({
      next: r => {
        this.testResults[channel] = r.data || r;
        this.testingChannels.delete(channel);
      },
      error: e => {
        this.testResults[channel] = { sent: false, detail: e.error?.message || 'Test failed.' };
        this.testingChannels.delete(channel);
      }
    });
  }

  save() {
    this.saving.set(true); this.success.set(''); this.error.set('');
    this.api.put<any>('/admin/features/channels', this.form).subscribe({
      next: r => {
        this.form = { ...r.data };
        this.success.set('Notification settings saved successfully!');
        this.saving.set(false);
        setTimeout(() => this.success.set(''), 4000);
      },
      error: e => {
        this.error.set(e.error?.message || 'Failed to save settings.');
        this.saving.set(false);
      }
    });
  }
}
