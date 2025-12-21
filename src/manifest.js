const { addonBuilder } = require("stremio-addon-sdk");

const manifest = {
    id: "org.osmanonline.stremio",
    version: "1.0.0",
    name: "Osman Online",
    description: "Watch Turkish historical dramas from OsmanOnline.info",
    resources: ["catalog", "meta", "stream"],
    types: ["series", "movie"], // Using movie type for episodes if needed, but usually series
    catalogs: [
        {
            type: "series",
            id: "osmanonline_catalog",
            name: "Osman Online Series",
            extra: [{ name: "search", isRequired: false }]
        }
    ],
    idPrefixes: ["osmanonline:"]
};

module.exports = manifest;
