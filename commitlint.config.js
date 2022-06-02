module.exports = {
    ignores: [
	(message) => message.includes('fix: bubble up chain')
    ],
    extends: ['@commitlint/config-conventional']
};
