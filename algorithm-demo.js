(function () {
  'use strict';
  const examples = [
    [31, 24, 28, 39, 33, 40, 3], [35, 29, 31, 43, 42, 46, null],
    [42, 36, null, 53, 48, 54, 5], [51, 41, 38, 59, 57, 62, null],
    [null, 46, 44, 62, null, 58, 6], [45, 35, 42, 48, 39, 51, null],
    [38, 28, 35, 45, 37, 44, 4], [34, 31, 29, 41, 34, 40, null]
  ];
  const names = ['screen', 'environment', 'posture', 'usage', 'eyes'];
  const rows = examples.map((values, index) => {
    const row = { observed_at: new Date(Date.UTC(2026, 8, 1 + index)).toISOString(),
      algorithm_version: 'synthetic-test-whatif', score: values[5], subjective_fatigue_nrs: values[6],
      screen_change: .03 + index * .008, continuous_minutes: 25 + index * 9, estimated_fields: [] };
    names.forEach((name, i) => { row[`${name}_score`] = values[i]; row[`${name}_quality`] = values[i] === null ? null : .6 + i * .06; });
    return row;
  });
  window.EyeCareAlgorithmEditor.mount(document.getElementById('algorithm-root'), { rows, mode: 'synthetic' });
})();
