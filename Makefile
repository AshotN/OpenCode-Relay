.PHONY: test build release release/patch release/minor release/major

test:
	./gradlew test

build:
	./gradlew buildPlugin

release/patch:
	./scripts/bump-version.sh --next-patch
	git push && git push --tags

release/minor:
	./scripts/bump-version.sh --next-minor
	git push && git push --tags

release/major:
	./scripts/bump-version.sh --next-major
	git push && git push --tags

release:
	@if [ -z "$(VERSION)" ]; then echo "Usage: make release VERSION=0.1.0"; exit 1; fi
	./scripts/bump-version.sh $(VERSION)
	git push && git push --tags
