# PostgreSQL Setup For TradeRakshak

TradeRakshak now uses PostgreSQL instead of SQLite.

## What The App Expects

Default database settings are:

- Database name: `traderakshak`
- User name: `traderakshak`
- Password: `CHANGE_ME`
- Server: `localhost`
- Port: `5432`

These values are stored in `.env`. If your PostgreSQL password is different, update `POSTGRES_PASSWORD` in `.env`.

## Easy Option: Docker

If Docker Desktop is installed, run:

```bat
docker compose up -d postgres
```

Then start the application with:

```bat
START.bat
```

## Manual PostgreSQL Option

If PostgreSQL is installed directly on Windows:

1. Open pgAdmin.
2. Create a user named `traderakshak`.
3. Set the password to `CHANGE_ME`, or update `.env` with your password.
4. Create a database named `traderakshak`.
5. Make `traderakshak` the owner of that database.
6. Run `START.bat`.

The application will create all required tables automatically on first start.

