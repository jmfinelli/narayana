#!/usr/bin/env python

import logging
import os

from utils.input_helper import get_boolean
from utils.input_helper import string_to_boolean


def check_deprecated_code(config):
    """Checks if there is deprecated code which should be removed"""
    release_timestamp = _get_major_release_timestamp()
    deprecations = _get_old_deprecations(config['source_path'], release_timestamp)
    if deprecations:
        logging.warning('Following deprecations are older than last major release: {0}'.format(', '.join(deprecations)))
    return not deprecations


def _get_major_release_timestamp():
    tag = os.popen("git tag --sort=-creatordate | grep -E '[0-9]+\\.0\\.0' | head -1").read().strip()
    if not tag:
        raise ValueError('No major release tag found')
    timestamp = os.popen('git for-each-ref --format="%(creatordate:unix)" refs/tags/{0}'.format(tag)).read().strip()
    if not timestamp:
        raise ValueError('Failed to get timestamp for tag: {0}'.format(tag))
    return int(timestamp)


def _get_old_deprecations(path, release_timestamp):
    query = r'find {0} -name \*.java | grep -v jboss-as | grep "src/main\|classes" | xargs grep -l "@Deprecated"'.format(
        path)
    return [file_name for file_name in os.popen(query).readlines() if
            release_timestamp > _get_last_commit_timestamp(file_name)]


def _get_last_commit_timestamp(file_name):
    query = 'git log -1 --pretty=format:%at -- {0}'.format(file_name)
    return int(os.popen(query).read())


def _get_configuration():
    return {
        'source_path': os.getenv('SOURCE_PATH', os.popen('git rev-parse --show-toplevel').read().strip()),
        'fail_deprecation': string_to_boolean(os.getenv('FAIL_DEPRECATION', 'False')),
        'ask_user': string_to_boolean(os.getenv('ASK_USER', 'True'))
    }


def main():
    logging.basicConfig(level=logging.INFO)
    config = _get_configuration()
    confirm_message = 'Pre release {0} check failed. Do you want to continue? \n'
    error_message = 'Pre release {0} check failed.'
    checks = [
        {'name': 'deprecation', 'function': check_deprecated_code, 'fail': config['fail_deprecation']}
    ]
    for check in checks:
        logging.info('Running %s check...', check['name'])
        if not check['function'](config):
            if config['ask_user']:
                if not get_boolean(confirm_message.format(check['name'])):
                    raise ValueError(error_message.format(check['name']))
            elif check['fail']:
                raise ValueError(error_message.format(check['name']))
        else:
            logging.info('%s check passed.', check['name'])


if __name__ == "__main__":
    main()
