import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { adminGuard } from './core/guards/admin.guard';

export const routes: Routes = [
  { path: '', redirectTo: '/dashboard', pathMatch: 'full' },
  { path: 'login',    loadComponent: () => import('./features/auth/login/login.component').then(m => m.LoginComponent) },
  { path: 'register', loadComponent: () => import('./features/auth/register/register.component').then(m => m.RegisterComponent) },
  {
    path: '',
    canActivate: [authGuard],
    children: [
      { path: 'dashboard',           loadComponent: () => import('./features/dashboard/dashboard.component').then(m => m.DashboardComponent) },
      { path: 'sector-intelligence', loadComponent: () => import('./features/sector-intelligence/sector-intelligence.component').then(m => m.SectorIntelligenceComponent) },
      { path: 'screener',            loadComponent: () => import('./features/screener/screener.component').then(m => m.ScreenerComponent) },
      { path: 'strategy',            loadComponent: () => import('./features/strategy/strategy.component').then(m => m.StrategyComponent) },
      { path: 'backtesting',         loadComponent: () => import('./features/backtesting/backtesting.component').then(m => m.BacktestingComponent) },
      { path: 'trading',             loadComponent: () => import('./features/trading/trading.component').then(m => m.TradingComponent) },
      { path: 'paper',               loadComponent: () => import('./features/paper-trading/paper-trading.component').then(m => m.PortfolioComponent) },
      { path: 'journal',             loadComponent: () => import('./features/journal/journal.component').then(m => m.JournalComponent) },
      { path: 'watchlist',           loadComponent: () => import('./features/watchlist/watchlist.component').then(m => m.WatchlistComponent) },
      { path: 'options',             loadComponent: () => import('./features/options/options.component').then(m => m.OptionsComponent) },
      { path: 'data',                loadComponent: () => import('./features/data-management/data-management.component').then(m => m.DataManagementComponent) },
      { path: 'stocks',              loadComponent: () => import('./features/stocks/stocks.component').then(m => m.StocksComponent) },
      { path: 'checklist',           loadComponent: () => import('./features/checklist/checklist.component').then(m => m.ChecklistComponent) },
      { path: 'events',              loadComponent: () => import('./features/events/events.component').then(m => m.EventsComponent) },
      { path: 'broker',              loadComponent: () => import('./features/broker/broker.component').then(m => m.BrokerComponent) },
      { path: 'competitions',        loadComponent: () => import('./features/competitions/competitions.component').then(m => m.CompetitionsComponent) },
      { path: 'subscription',        loadComponent: () => import('./features/subscription/subscription.component').then(m => m.SubscriptionComponent) },
      { path: 'pricing',             loadComponent: () => import('./features/billing/pricing.component').then(m => m.PricingComponent) },
      { path: 'billing',             loadComponent: () => import('./features/billing/billing.component').then(m => m.BillingComponent) },
      { path: 'profile',             loadComponent: () => import('./features/profile/profile.component').then(m => m.ProfileComponent) },
      { path: 'reports',             loadComponent: () => import('./features/reports/reports.component').then(m => m.ReportsComponent) },
      { path: 'issues',              loadComponent: () => import('./features/issues/issues.component').then(m => m.IssuesComponent) },
      { path: 'feedback',            loadComponent: () => import('./features/feedback/feedback.component').then(m => m.FeedbackComponent) },
      { path: 'user-hierarchy',      loadComponent: () => import('./features/user-hierarchy/user-hierarchy.component').then(m => m.UserHierarchyComponent) },

      // ── Admin Feature Config ────────────────────────────────────────────────
      { path: 'admin/feature-config', loadComponent: () => import('./features/admin-feature-config/admin-feature-config.component').then(m => m.AdminFeatureConfigComponent) },
      // ── User Notification Settings ──────────────────────────────────────────
      { path: 'notifications',        loadComponent: () => import('./features/notification-settings/notification-settings.component').then(m => m.NotificationSettingsComponent) },

      // ── Technical Indicator Engine Settings ─────────────────────────────────
      { path: 'indicator-settings',   loadComponent: () => import('./features/indicator-settings/indicator-settings.component').then(m => m.IndicatorSettingsComponent) },

      // ── Enterprise Modules ───────────────────────────────────────────────────
      { path: 'portfolio',           loadComponent: () => import('./features/paper-trading/paper-trading.component').then(m => m.PortfolioComponent) },

      // ── Stock Upload & Performance Tracker
      { path: 'stock-tracker', loadComponent: () => import('./features/stock-tracker/stock-tracker.component').then(m => m.StockTrackerComponent) },

      // ── Stock Tracker Dashboard (separate from upload)
      { path: 'tracker-dashboard', loadComponent: () => import('./features/tracker-dashboard/tracker-dashboard.component').then(m => m.TrackerDashboardComponent) },

      // ── Market Breadth Dashboard
      { path: 'market-breadth', loadComponent: () => import('./features/market-breadth/market-breadth.component').then(m => m.MarketBreadthComponent) },

      // ── Stock Ranking Engine ────────────────────────────────────────────────
      { path: 'stock-ranking',         loadComponent: () => import('./features/stock-ranking/stock-ranking.component').then(m => m.StockRankingComponent) },

      // ── Strategy Builder ────────────────────────────────────────────────────
      { path: 'strategy-builder',              loadComponent: () => import('./features/strategy-builder/strategy-hub.component').then(m => m.StrategyHubComponent) },
      { path: 'strategy-builder/simple',       redirectTo: 'strategy-builder', pathMatch: 'full' },
      { path: 'strategy-builder/advanced',     redirectTo: 'strategy-builder', pathMatch: 'full' },
      { path: 'strategy-builder/ai',           redirectTo: 'strategy-builder', pathMatch: 'full' },
      { path: 'strategy-builder/backtest/:id', redirectTo: 'strategy-builder' },
      { path: 'strategy-builder/backtesting',  redirectTo: 'strategy-builder', pathMatch: 'full' },
      { path: 'strategy-builder/my-strategies',redirectTo: 'strategy-builder', pathMatch: 'full' },
      { path: 'strategy-builder/marketplace',  redirectTo: 'strategy-builder', pathMatch: 'full' },
      { path: 'strategy-builder/live-alerts',  redirectTo: 'strategy-builder', pathMatch: 'full' },
      { path: 'strategy-builder/performance',  redirectTo: 'strategy-builder', pathMatch: 'full' },
      { path: 'strategy-builder/versions',     redirectTo: 'strategy-builder', pathMatch: 'full' },
      { path: 'strategy-builder/optimization', redirectTo: 'strategy-builder', pathMatch: 'full' },
      { path: 'strategy-builder/visual',       redirectTo: 'strategy-builder', pathMatch: 'full' },
      { path: 'strategy-builder/paper',        redirectTo: 'strategy-builder', pathMatch: 'full' },
      { path: 'strategy-builder/edit/:id',     redirectTo: 'strategy-builder' },

      // ── System Settings (Admin) ─────────────────────────────────────────────
      { path: 'system-settings',     canActivate: [adminGuard], loadComponent: () => import('./features/system-settings/system-settings.component').then(m => m.SystemSettingsComponent) },

      // Admin routes
      { path: 'users',               canActivate: [adminGuard], loadComponent: () => import('./features/users/users.component').then(m => m.UsersComponent) },
      { path: 'agents',              canActivate: [adminGuard], loadComponent: () => import('./features/agents/agents.component').then(m => m.AgentsComponent) },
      { path: 'ml-engine',           canActivate: [adminGuard], loadComponent: () => import('./features/ml-engine/ml-engine.component').then(m => m.MlEngineComponent) },
      { path: 'admin/unusual-alerts',canActivate: [adminGuard], loadComponent: () => import('./features/unusual-alerts/unusual-alerts.component').then(m => m.UnusualAlertsComponent) },
      { path: 'communication',       canActivate: [adminGuard], loadComponent: () => import('./features/communication/communication.component').then(m => m.CommunicationComponent) },
      { path: 'project-health',      canActivate: [adminGuard], loadComponent: () => import('./features/project-health/project-health.component').then(m => m.ProjectHealthComponent) },
    ]
  },
  { path: '**', redirectTo: '/dashboard' }
];
