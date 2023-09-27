
git rev-parse HEAD

for f in $1/*/transcript.*
do
	head -n 1 $f
	break
done

for f in $1/*/transcript.*
do
	head -n 2 $f | tail -n 1
done
