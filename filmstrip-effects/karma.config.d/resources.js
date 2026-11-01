// The test resources land beside the test bundle in kotlin/, and Karma serves only the files it lists. This lists
// the fonts there without loading them as scripts, so a test fetches one from /base/kotlin/.
const path = require('path');
config.files.push({
  pattern: path.join(config.basePath, 'kotlin', '*.ttf'),
  included: false,
  served: true,
  watched: false,
});
