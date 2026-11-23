// Chạy một lần khi volume Mongo còn trống. Mỗi dịch vụ một user, chỉ readWrite + collMod trên database của mình.
const services = [
  { db: "learninge_content", user: "content", password: process.env.MONGO_CONTENT_PASSWORD },
  { db: "learninge_game", user: "game", password: process.env.MONGO_GAME_PASSWORD },
  { db: "learninge_insight", user: "insight", password: process.env.MONGO_INSIGHT_PASSWORD },
];

for (const s of services) {
  const database = db.getSiblingDB(s.db);
  database.createRole({
    role: "serviceRole",
    privileges: [{ resource: { db: s.db, collection: "" }, actions: ["collMod"] }],
    roles: [{ role: "readWrite", db: s.db }],
  });
  database.createUser({ user: s.user, pwd: s.password, roles: [{ role: "serviceRole", db: s.db }] });
}
