import * as core from '@actions/core';
import * as github from '@actions/github';

async function run() {
  try {
    const type: string = core.getInput('type', {required: true});
    const regex: string = core.getInput('regex', {required: true});
    const message: string = core.getInput('message', {required: true});

    if (type !== 'title' && type !== 'body') {
      throw new Error(
        '`type` must be either "title" or "body".'
      );
    }

    // Get client and context
    const client: github.GitHub = new github.GitHub(
      core.getInput('repo-token', {required: true})
    );
    const context = github.context;
    const payload = context.payload;

    // Do nothing if it's wasn't being opened or it's not an issue
    if (payload.action !== 'opened' || !payload.issue) {
      return;
    }

    if (!payload.sender) {
      throw new Error('Internal error, no sender provided by GitHub');
    }

    const issue: {owner: string; repo: string; number: number} = context.issue;

    const text = type === 'title' ? payload?.issue?.title : payload?.issue?.body;
    const regexMatches: boolean = check(regex, text);

    if (regexMatches) {
      // Comment and close
      await client.issues.createComment({
        owner: issue.owner,
        repo: issue.repo,
        issue_number: issue.number,
        body: evalTemplate(message, payload)
      });
      await client.issues.update({
        owner: issue.owner,
        repo: issue.repo,
        issue_number: issue.number,
        state: 'closed'
      });
    }
  } catch (error) {
    core.setFailed(error.message);
  }
}

function check(patternString: string, text: string | undefined): boolean {
  const pattern: RegExp = new RegExp(patternString);

  if (text?.match(pattern)) {
    return true;
  } else {
    return false;
  }
}

function evalTemplate(template: string, params: any) {
  return Function(...Object.keys(params), `return \`${template}\``)(...Object.values(params));
}

run();
