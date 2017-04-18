#!/usr/bin/env python3

import argparse
import os
import shlex
import subprocess
import sys


def check_output(args):
    bytes = subprocess.check_output(args)
    return bytes.decode('utf-8')


def check_output_line(args):
    return check_output(args).strip()


def check_output_lines(args):
    return [file.strip() for file in check_output(args).split('\n')]


def parse_name_status(lines):
    files = []
    for line in lines:
        if len(line) == 0 or line[0] == 'D':
            continue
        name = line[1:].strip()
        files.append(name)
    return files


def remote_communicate(args, remote_command):
    args = ['ssh', args.host, remote_command]
    proc = subprocess.Popen(args, shell=False)
    proc.communicate()
    if proc.returncode != 0:
        sys.exit(proc.returncode)


def check_remote_files(args, files):
    remote_command = "cd {0} && git diff --name-status".format(args.remote_path)
    remote_changed = parse_name_status(check_output_lines(['ssh', args.host, remote_command]))
    unexpected = []
    for changed in remote_changed:
        if changed not in files:
            unexpected.append(changed)
    if unexpected:
        command = 'cd {0}'.format(args.remote_path)
        message = 'Reverting:\n'
        for file in unexpected:
            message += '  {0}\n'.format(file)
            command += ' && git checkout -- {0}'.format(shlex.quote(file))
        print(message)
        remote_communicate(args, command)


def main():
    parser = argparse.ArgumentParser(prog=sys.argv[0])
    parser.add_argument('--host', type=str, default=None, help='host for build')
    parser.add_argument('--remote-path',
                        type=str,
                        default='~/code/yugabyte',
                        help='path used for build')
    parser.add_argument('--branch', type=str, default='master', help='base branch for build')
    parser.add_argument('--build-type', type=str, default='debug', help='build type')
    parser.add_argument('--skip-build',
                        action='store_const',
                        const=True,
                        default=False,
                        help='skip build, only sync files')
    parser.add_argument('args', nargs=argparse.REMAINDER, help='arguments for yb_build.sh')

    args = parser.parse_args()

    host = args.host
    if host is None and "YB_REMOTE_BUILD_HOST" in os.environ:
        host = os.environ["YB_REMOTE_BUILD_HOST"]

    if host is None:
        sys.stderr.write(
            "Please specify host with --host option or YB_REMOTE_BUILD_HOST variable\n")
        sys.exit(1)

    commit = check_output_line(['git', 'merge-base', args.branch, 'HEAD'])
    print("Base commit: {0}".format(commit))

    files = parse_name_status(check_output_lines(['git', 'diff', commit, '--name-status']))
    print("Total files: {0}".format(len(files)))

    if files:
        rsync_args = ['rsync', '-avR']
        rsync_args += files
        rsync_args += ["{0}:{1}".format(args.host, args.remote_path)]
        proc = subprocess.Popen(rsync_args, shell=False)
        proc.communicate()
        if proc.returncode != 0:
            sys.exit(proc.returncode)

    check_remote_files(args, files)

    if args.skip_build:
        sys.exit(0)

    ybd_args = [args.build_type]
    if len(args.args) != 0 and args.args[0] == '--':
        ybd_args += args.args[1:]
    else:
        ybd_args += args.args
    remote_command = "cd {0} && ./yb_build.sh".format(args.remote_path)
    for arg in ybd_args:
        remote_command += " {0}".format(shlex.quote(arg))
    print("Remote command: {0}".format(remote_command))
    ssh_args = ['ssh', args.host, remote_command]
    proc = subprocess.Popen(ssh_args, shell=False)
    proc.communicate()
    if proc.returncode != 0:
        sys.exit(proc.returncode)


if __name__ == '__main__':
    main()
