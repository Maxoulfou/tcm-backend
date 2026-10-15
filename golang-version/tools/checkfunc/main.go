package main

import (
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"os"
	"path/filepath"
)

func main() {
	root := "."
	if len(os.Args) > 1 {
		root = os.Args[1]
	}
	if err := checkRoot(root); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
func checkRoot(root string) error {
	files := token.NewFileSet()
	count, e := walk(root, files)
	if e != nil {
		return e
	}
	if count > 0 {
		return fmt.Errorf("%d function(s) exceed 20 lines", count)
	}
	return nil
}
func walk(root string, files *token.FileSet) (int, error) {
	count := 0
	e := filepath.WalkDir(root, func(path string, entry fs.DirEntry, e error) error { return visit(files, path, entry, e, &count) })
	return count, e
}
func visit(files *token.FileSet, path string, entry fs.DirEntry, e error, count *int) error {
	if e != nil {
		return e
	}
	if entry.IsDir() || filepath.Ext(path) != ".go" {
		return nil
	}
	n, e := checkFile(files, path)
	*count += n
	return e
}
func checkFile(files *token.FileSet, path string) (int, error) {
	file, e := parser.ParseFile(files, path, nil, parser.ParseComments)
	if e != nil {
		return 0, e
	}
	count := 0
	ast.Inspect(file, func(n ast.Node) bool { count += checkNode(files, path, n); return true })
	return count, nil
}
func checkNode(files *token.FileSet, path string, n ast.Node) int {
	switch fn := n.(type) {
	case *ast.FuncDecl:
		return checkLength(files, path, fn.Pos(), fn.End())
	case *ast.FuncLit:
		return checkLength(files, path, fn.Pos(), fn.End())
	}
	return 0
}
func checkLength(files *token.FileSet, path string, start, end token.Pos) int {
	first, last := files.Position(start).Line, files.Position(end).Line
	if last-first+1 <= 20 {
		return 0
	}
	fmt.Fprintf(os.Stderr, "%s:%d-%d: function has %d lines (limit 20)\n", path, first, last, last-first+1)
	return 1
}
