import firebaseRulesPlugin from '@firebase/eslint-plugin-security-rules';

export default [
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      'functions/lib/**',
      'functions/node_modules/**',
      'SABQ_AI/**',
      'android/**',
    ],
  },
  firebaseRulesPlugin.configs['flat/recommended'],
];
