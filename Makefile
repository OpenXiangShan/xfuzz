default: build

build:
	@cargo make build-all

init:
	git submodule update --init

rebuild:
	@cargo make --quiet rebuild

%:
	@cargo make $@

Makefile: ;
