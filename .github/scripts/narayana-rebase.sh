function fatal {
  echo "$1"
  exit 1
}

function rebase_narayana {
  echo "Rebasing Narayana"
  cd $WORKSPACE

  # Clean up the local repo
  git rebase --abort
  rm -rf .git/rebase-apply
  git clean -f -d -x

  # Work out the branch point
  git branch -D main
  git branch main origin/main

  export BRANCHPOINT=main

  # Update the pull to head
  git pull --rebase --ff-only origin $BRANCHPOINT

  if [ $? -ne 0 ]; then
    fatal "Narayana rebase on $BRANCHPOINT failed. Please rebase it manually"
  fi
}

rebase_narayana "$@"
exit 0
