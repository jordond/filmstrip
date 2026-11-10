// The published fixtures land beside the test bundle in kotlin/ as test resources, and Karma serves only the files it
// lists. This lists the clips there without loading them as scripts, so a test fetches one from /base/kotlin/.
const path = require('path');
config.files.push({
  pattern: path.join(config.basePath, 'kotlin', '*.{mp4,mkv,ts}'),
  included: false,
  served: true,
  watched: false,
});
