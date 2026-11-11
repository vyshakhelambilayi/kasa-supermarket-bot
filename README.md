# KaSa Supermarket Bot

KaSa is a Telegram-based AI assistant that helps local shops take customer orders without requiring the owner to handle every message. Customers can ask about product availability and build an order through a chat. KaSa interprets text, voice messages, and photos, checks requested products against the shop's PostgreSQL inventory, and confirms orders only after the customer reviews them.

## What it does

- Accepts order requests as text, voice messages, or photos in Telegram.
- Uses Google's Gemini API to interpret requests and changes to a pending order.
- Matches requested products to the shop's inventory and reports availability and prices.
- Lets customers edit a pending order, then explicitly confirm or cancel it.
- Registers customers using a shared phone number and name when needed to place an order. Customers can check availability without registering.
- Saves registered customers' pending order drafts so they can be restored later.
- Writes confirmed orders to PostgreSQL and updates inventory stock in the same transaction.
- Provides a basic HTTP health endpoint (`/`) that responds with `ok`.

## How an order works

1. A customer opens the Telegram bot and sends a product request.
2. KaSa interprets the request, checks matching inventory items, and replies with an order draft or availability information.
3. To place an order, the customer shares their phone number. If no customer account matches, KaSa asks for their name and creates one.
4. The customer can request changes, then send `/confirm` when the draft is ready. `/cancel` discards the pending order.
5. KaSa records the confirmed order and deducts the ordered quantities from inventory. If stock has changed, confirmation fails safely and the customer is asked to check availability again.

## Technology

- Java (Maven project; source/target compatibility set to Java 17)
- Telegram Bots Java library, using long polling
- Google GenAI SDK / Gemini
- PostgreSQL with JDBC and the `pg_trgm` extension for product-name matching
- Docker (the included image builds and runs on Eclipse Temurin 21)

## Prerequisites

To run the application, you need:

- A Telegram bot token, created through [BotFather](https://t.me/BotFather).
- A Google AI API key with access to the Gemini model used by the application.
- A PostgreSQL database with the shop's `public.customers` and `public.inventory` tables, including the columns expected by this application. The repository's SQL migrations add order and order-draft tables; they do not create or populate the shop's customer and inventory tables.
- Java 17 or later and Maven 3.9+ for a local build, or Docker for a containerized build.

## Configuration

Set these environment variables before starting the application:

| Variable | Required | Default / purpose |
| --- | --- | --- |
| `TELEGRAM_BOT_TOKEN` | Yes | Token from BotFather. |
| `TELEGRAM_BOT_USERNAME` | No | Bot username; defaults to `BOT_USERNAME`. Set this to your actual bot username. |
| `GOOGLE_API_KEY` or `GEMINI_API_KEY` | Yes | Google AI API key used by the GenAI SDK. |
| `KASA_DB_PASSWORD` | Yes | Password for the PostgreSQL application user. |
| `KASA_DB_URL` | No | JDBC URL; defaults to `jdbc:postgresql://localhost:5432/kasadb`. |
| `KASA_DB_USER` | No | Database user; defaults to `kasabot`. |
| `PORT` | No | HTTP health-server port; defaults to `8080`. |

Keep API keys, bot tokens, and database credentials private. Do not commit them to the repository.

## Database setup

The SQL migration scripts are in `src/main/resources/db/migration`. Apply them to the target database in version order using a database account with permission to create tables, indexes, and the `pg_trgm` extension:

1. `V1__create_order_tables.sql` creates the confirmed-order tables and grants the bot database role the required order and inventory permissions.
2. `V2__inventory_trigram_index.sql` adds the trigram index used for fuzzy inventory-name search (the `pg_trgm` extension must be available).
3. `V3__create_order_drafts.sql` creates storage for registered customers' in-progress order drafts.
4. `V4__persist_unavailable_draft_items.sql` adds support for persisting unavailable items in drafts.
5. `V5__allow_customer_registration.sql` updates permissions for customer registration.

These are plain SQL scripts; apply them once, in order, and check their contents against your database schema before running them. The application expects the existing `public.customers` table to provide `customer_id`, `name`, and `phone_number`, and `public.inventory` to provide `item_number`, `item_name`, `price`, and `quantity_available`.

## Build and run locally

Build the executable JAR and run the test suite:

```bash
mvn clean package
```

Set the environment variables above, then start the application:

```bash
java -jar target/kasa-bot-1.0-SNAPSHOT-jar-with-dependencies.jar
```

The bot uses Telegram long polling. The health endpoint listens on `PORT` (or port `8080` by default) and responds `ok` at `/`.

## Run with Docker

Build the image from the repository root:

```bash
docker build -t kasa-supermarket-bot .
```

Run it with your own credentials and database URL:

```bash
docker run --rm \
  -e TELEGRAM_BOT_TOKEN="your-telegram-bot-token" \
  -e TELEGRAM_BOT_USERNAME="your-bot-username" \
  -e GOOGLE_API_KEY="your-google-api-key" \
  -e KASA_DB_URL="jdbc:postgresql://your-database-host:5432/kasadb" \
  -e KASA_DB_USER="kasabot" \
  -e KASA_DB_PASSWORD="your-database-password" \
  -e PORT=8080 \
  -p 8080:8080 \
  kasa-supermarket-bot
```

For production, inject secrets through your deployment platform's secret manager rather than placing them in shell history or a committed file. If PostgreSQL runs on the host machine, `localhost` inside a container refers to that container; configure a database host reachable from the container.

## Telegram commands

| Command | Description |
| --- | --- |
| `/start` | Start interacting with KaSa and see how to share a phone number. |
| `/phone` | Share a phone number to register or link an account. |
| `/update_phone` | Update the phone number linked to the current customer account. |
| `/confirm` | Place the current order draft, if it is ready and the customer is registered. |
| `/cancel` | Cancel registration or the pending order. |

## Project layout

```text
src/main/java/com/kasa/
├── controller/       Telegram chat handling
├── model/            Customer, inventory, and order data models
├── repository/       PostgreSQL data access
├── service/          Customer, product, and order workflows
└── service/ai/       Gemini order interpretation and prompts

src/main/resources/db/migration/  SQL schema migrations
src/test/                         Automated tests
Dockerfile                        Multi-stage container build
pom.xml                           Maven build and dependencies
```

## Contributing

Issues and pull requests are welcome. Before opening a pull request, run `mvn clean package` and describe any environment or database changes needed to test it.
