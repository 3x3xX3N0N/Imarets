// Self-hosted fonts from npm (@fontsource/*). Added as extra webpack entry modules so the CSS + woff2
// files are emitted next to site.js (main.css + fonts/*). No CDN, no remote fonts.
(function () {
    const fonts = [
        '@fontsource/archivo/400.css',
        '@fontsource/archivo/700.css',
        '@fontsource/archivo-black/400.css',
        '@fontsource/jetbrains-mono/400.css',
        '@fontsource/jetbrains-mono/700.css',
    ];
    const current = Array.isArray(config.entry.main) ? config.entry.main : [config.entry.main];
    config.entry.main = fonts.concat(current);
    // font files referenced from the CSS become hashed files under fonts/
    config.module.rules.push({
        test: /\.(woff2?|ttf|otf)$/i,
        type: 'asset/resource',
        generator: { filename: 'fonts/[name].[contenthash:8][ext]' },
    });
})();
