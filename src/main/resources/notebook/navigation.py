"""Bounded, static Python navigation helper.

Protocol: one JSON request on stdin, one JSON result on stdout.  Target modules are located with
PathFinder and parsed with ast; they are never imported or executed.
"""

import ast
import importlib.util
import io
import json
import os
import re
import sys
import token
import tokenize
from importlib.machinery import PathFinder


SCOPE_NODES = (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda, ast.ClassDef)
FUNCTION_NODES = (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda)
IGNORED_TOKENS = {
    token.INDENT,
    token.DEDENT,
    token.NEWLINE,
    tokenize.NL,
    tokenize.COMMENT,
    token.ENDMARKER,
}


_IPYTHON_PREFIX = re.compile(r"^(?:[%!?]|[A-Za-z_]\w*\s*=\s*[!%])")
_IPYTHON_HELP = re.compile(r"^[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*\?{1,2}\s*(?:#.*)?$")


def _line_parts(line):
    if line.endswith("\r\n"):
        return line[:-2], "\r\n"
    if line.endswith(("\n", "\r")):
        return line[:-1], line[-1:]
    return line, ""


def _mask_ipython_statement(line, preserve_indent=True):
    """Replace an IPython-only statement with a same-length Python expression."""
    body, ending = _line_parts(line)
    if not body:
        return ending
    if not preserve_indent:
        return "0" + " " * (len(body) - 1) + ending
    indent = len(body) - len(body.lstrip(" \t"))
    if indent >= len(body):
        return body + ending
    return body[:indent] + "0" + " " * (len(body) - indent - 1) + ending


def _multiline_string_lines(source):
    """Lines whose leading punctuation is data inside a Python string."""
    protected = set()
    tokens = tokenize.generate_tokens(io.StringIO(source).readline)
    while True:
        try:
            item = next(tokens)
        except (StopIteration, tokenize.TokenError, IndentationError):
            break
        if item.type == token.STRING and item.start[0] != item.end[0]:
            protected.update(range(item.start[0] - 1, item.end[0]))
    return protected


def mask_ipython_source(source):
    """Make common IPython syntax parseable without moving any source offset.

    Line/cell magics, shell escapes and help queries are valid notebook input but
    invalid Python grammar. They do not define statically trustworthy Python
    bindings, so substitute a harmless expression of exactly the same length.
    A cell magic owns the remainder of its cell and is masked in full.
    """
    result = []
    cell_magic = False
    continuation = False
    string_lines = _multiline_string_lines(source)
    for line_index, line in enumerate(source.splitlines(True)):
        body, _ending = _line_parts(line)
        stripped = body.lstrip(" \t")
        protected_string = line_index in string_lines and not cell_magic
        starts_cell_magic = not protected_string and not cell_magic and stripped.startswith("%%")
        special = (
            cell_magic
            or continuation
            or starts_cell_magic
            or (
                not protected_string
                and (bool(_IPYTHON_PREFIX.match(stripped)) or bool(_IPYTHON_HELP.match(stripped)))
            )
        )
        if special:
            result.append(
                _mask_ipython_statement(
                    line,
                    preserve_indent=not (cell_magic or starts_cell_magic),
                )
            )
            continuation = not (cell_magic or starts_cell_magic) and body.rstrip().endswith("\\")
        else:
            result.append(line)
            continuation = False
        if starts_cell_magic:
            cell_magic = True
    return "".join(result)


def emit(result):
    sys.stdout.write(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
    sys.stdout.flush()


def unresolved(reason, message):
    return {
        "status": "unresolved",
        "reason": reason,
        "message": str(message)[:4096],
    }


class SourceUnit:
    def __init__(self, identity, source, path=None, module_name=None, is_package=False):
        self.identity = identity
        self.source = source
        self.path = path
        self.module_name = module_name
        self.is_package = is_package
        self.is_notebook = path is None
        self.lines = source.splitlines(True)
        if not self.lines:
            self.lines = [""]
        self.line_offsets = []
        offset = 0
        for line in self.lines:
            self.line_offsets.append(offset)
            offset += len(line)
        parse_source = mask_ipython_source(source) if self.is_notebook else source
        self.tree = ast.parse(parse_source, filename=path or "<notebook-cell>")
        # Tokenize the same offset-preserving source that was parsed. IPython
        # commands accept arbitrary shell/magic syntax (for example `!echo (`),
        # which can leave Python's tokenizer in an unterminated state even
        # though the following notebook lines are valid Python.
        self.tokens = list(tokenize.generate_tokens(io.StringIO(parse_source).readline))
        self.parents = {}
        for parent in ast.walk(self.tree):
            for child in ast.iter_child_nodes(parent):
                self.parents[child] = parent
        self._scope_cache = {}

    def absolute_offset(self, line, column):
        if line < 0:
            return 0
        if line >= len(self.line_offsets):
            return len(self.source)
        return min(len(self.source), self.line_offsets[line] + column)

    def position_at(self, offset):
        offset = max(0, min(len(self.source), offset))
        before = self.source[:offset]
        line = before.count("\n")
        newline = before.rfind("\n")
        return line, offset if newline < 0 else offset - newline - 1

    def ast_column(self, line_one_based, byte_column):
        line_index = max(0, line_one_based - 1)
        if line_index >= len(self.lines):
            return 0
        encoded = self.lines[line_index].encode("utf-8")
        prefix = encoded[: max(0, byte_column)]
        return len(prefix.decode("utf-8", errors="ignore"))

    def node_start(self, node):
        line = max(0, getattr(node, "lineno", 1) - 1)
        column = self.ast_column(getattr(node, "lineno", 1), getattr(node, "col_offset", 0))
        return line, column

    def node_end(self, node):
        line_number = getattr(node, "end_lineno", getattr(node, "lineno", 1))
        byte_column = getattr(node, "end_col_offset", getattr(node, "col_offset", 0))
        return max(0, line_number - 1), self.ast_column(line_number, byte_column)

    def node_start_offset(self, node):
        line, column = self.node_start(node)
        return self.absolute_offset(line, column)

    def node_end_offset(self, node):
        line, column = self.node_end(node)
        return self.absolute_offset(line, column)

    def name_position(self, node, name, prefer_last=False):
        if isinstance(node, (ast.Name, ast.arg)):
            return self.node_start(node)
        start = self.node_start_offset(node)
        end = max(start, self.node_end_offset(node))
        matches = []
        for item in self.tokens:
            if item.type != token.NAME or item.string != name:
                continue
            item_offset = self.absolute_offset(item.start[0] - 1, item.start[1])
            if start <= item_offset <= end:
                matches.append((item.start[0] - 1, item.start[1]))
        if matches:
            return matches[-1] if prefer_last else matches[0]
        return self.node_start(node)

    def location(self, line, column, symbol):
        result = {
            "status": "notebook" if self.is_notebook else "file",
            "line": line,
            "column": column,
            "symbol": symbol,
        }
        if self.is_notebook:
            result["cellId"] = self.identity
        else:
            result["path"] = self.path
        return result

    def contains_offset(self, node, offset):
        return self.node_start_offset(node) <= offset <= self.node_end_offset(node)

    def body_start_offset(self, node):
        if isinstance(node, ast.Lambda):
            return self.node_start_offset(node.body)
        body = getattr(node, "body", None)
        if body:
            return self.node_start_offset(body[0])
        return self.node_end_offset(node)

    def scope_at(self, offset):
        candidates = []
        for node in ast.walk(self.tree):
            if not isinstance(node, SCOPE_NODES):
                continue
            if self.body_start_offset(node) <= offset <= self.node_end_offset(node):
                candidates.append(node)
        if not candidates:
            return self.tree
        candidates.sort(key=lambda item: self.node_end_offset(item) - self.node_start_offset(item))
        return candidates[0]

    def scope_chain(self, offset):
        scope = self.scope_at(offset)
        if isinstance(scope, ast.Module):
            return [self.tree]
        chain = [scope]
        skip_classes = isinstance(scope, FUNCTION_NODES)
        current = scope
        while current in self.parents:
            current = self.parents[current]
            if not isinstance(current, SCOPE_NODES):
                continue
            if isinstance(current, ast.ClassDef) and skip_classes:
                continue
            chain.append(current)
            if isinstance(current, FUNCTION_NODES):
                skip_classes = False
        chain.append(self.tree)
        return chain


class Binding:
    def __init__(
        self,
        name,
        kind,
        unit,
        node,
        line,
        column,
        available_offset=None,
        value=None,
        module=None,
        imported=None,
        level=0,
        conditional=False,
    ):
        self.name = name
        self.kind = kind
        self.unit = unit
        self.node = node
        self.line = line
        self.column = column
        self.offset = unit.absolute_offset(line, column)
        self.available_offset = self.offset if available_offset is None else available_offset
        self.value = value
        self.module = module
        self.imported = imported
        self.level = level
        self.conditional = conditional

    def location(self):
        return self.unit.location(self.line, self.column, self.name)


class ScopeBindings:
    def __init__(self):
        self.bindings = []
        self.globals = set()
        self.nonlocals = set()


def target_names(target):
    if isinstance(target, (ast.Name, ast.arg)):
        return [target]
    if isinstance(target, (ast.Tuple, ast.List)):
        result = []
        for item in target.elts:
            result.extend(target_names(item))
        return result
    if isinstance(target, ast.Starred):
        return target_names(target.value)
    return []


def scope_bindings(unit, scope):
    cache_key = id(scope)
    if cache_key in unit._scope_cache:
        return unit._scope_cache[cache_key]
    result = ScopeBindings()
    unit._scope_cache[cache_key] = result

    def add_target(target, kind, owner, value=None, available_offset=None):
        for name_node in target_names(target):
            line, column = unit.node_start(name_node)
            result.bindings.append(
                Binding(
                    name_node.id if isinstance(name_node, ast.Name) else name_node.arg,
                    kind,
                    unit,
                    owner,
                    line,
                    column,
                    available_offset=available_offset,
                    value=value,
                )
            )

    def scan_expression(expression):
        if expression is None:
            return
        if isinstance(expression, (ast.Lambda, ast.ListComp, ast.SetComp, ast.DictComp, ast.GeneratorExp)):
            return
        if isinstance(expression, ast.NamedExpr):
            add_target(
                expression.target,
                "assignment",
                expression,
                value=expression.value,
                available_offset=unit.node_end_offset(expression),
            )
        for child in ast.iter_child_nodes(expression):
            scan_expression(child)

    def mark_conditional_since(start):
        for binding in result.bindings[start:]:
            binding.conditional = True

    def scan_statements(statements):
        for statement in statements:
            if isinstance(statement, (ast.FunctionDef, ast.AsyncFunctionDef)):
                line, column = unit.name_position(statement, statement.name)
                result.bindings.append(Binding(statement.name, "function", unit, statement, line, column))
                continue
            if isinstance(statement, ast.ClassDef):
                line, column = unit.name_position(statement, statement.name)
                result.bindings.append(Binding(statement.name, "class", unit, statement, line, column))
                continue
            if isinstance(statement, ast.Import):
                for alias in statement.names:
                    name = alias.asname or alias.name.split(".")[0]
                    line, column = unit.name_position(statement, name, prefer_last=alias.asname is not None)
                    result.bindings.append(
                        Binding(
                            name,
                            "import",
                            unit,
                            statement,
                            line,
                            column,
                            available_offset=unit.node_end_offset(statement),
                            module=alias.name if alias.asname else alias.name.split(".")[0],
                        )
                    )
                continue
            if isinstance(statement, ast.ImportFrom):
                for alias in statement.names:
                    if alias.name == "*":
                        line, column = unit.node_start(statement)
                        result.bindings.append(
                            Binding(
                                "*",
                                "star_import",
                                unit,
                                statement,
                                line,
                                column,
                                available_offset=unit.node_end_offset(statement),
                                module=statement.module,
                                level=statement.level,
                            )
                        )
                        continue
                    name = alias.asname or alias.name
                    line, column = unit.name_position(statement, name, prefer_last=alias.asname is not None)
                    result.bindings.append(
                        Binding(
                            name,
                            "from_import",
                            unit,
                            statement,
                            line,
                            column,
                            available_offset=unit.node_end_offset(statement),
                            module=statement.module,
                            imported=alias.name,
                            level=statement.level,
                        )
                    )
                continue
            if isinstance(statement, ast.Assign):
                scan_expression(statement.value)
                available = unit.node_end_offset(statement)
                for target in statement.targets:
                    add_target(target, "assignment", statement, statement.value, available)
                continue
            if isinstance(statement, ast.AnnAssign):
                scan_expression(statement.value)
                add_target(
                    statement.target,
                    "assignment",
                    statement,
                    statement.value,
                    unit.node_end_offset(statement),
                )
                continue
            if isinstance(statement, ast.AugAssign):
                scan_expression(statement.value)
                add_target(
                    statement.target,
                    "assignment",
                    statement,
                    None,
                    unit.node_end_offset(statement),
                )
                continue
            if isinstance(statement, (ast.For, ast.AsyncFor)):
                scan_expression(statement.iter)
                conditional_start = len(result.bindings)
                add_target(statement.target, "assignment", statement, None, unit.node_start_offset(statement.target))
                scan_statements(statement.body)
                scan_statements(statement.orelse)
                mark_conditional_since(conditional_start)
                continue
            if isinstance(statement, (ast.With, ast.AsyncWith)):
                for item in statement.items:
                    scan_expression(item.context_expr)
                    if item.optional_vars is not None:
                        add_target(
                            item.optional_vars,
                            "assignment",
                            statement,
                            None,
                            unit.node_start_offset(item.optional_vars),
                        )
                scan_statements(statement.body)
                continue
            if isinstance(statement, (ast.If, ast.While)):
                scan_expression(statement.test)
                conditional_start = len(result.bindings)
                scan_statements(statement.body)
                scan_statements(statement.orelse)
                mark_conditional_since(conditional_start)
                continue
            if isinstance(statement, (ast.Try, getattr(ast, "TryStar", ast.Try))):
                conditional_start = len(result.bindings)
                scan_statements(statement.body)
                for handler in statement.handlers:
                    if handler.name:
                        line, column = unit.name_position(handler, handler.name, prefer_last=True)
                        result.bindings.append(
                            Binding(
                                handler.name,
                                "assignment",
                                unit,
                                handler,
                                line,
                                column,
                            )
                        )
                    scan_statements(handler.body)
                scan_statements(statement.orelse)
                mark_conditional_since(conditional_start)
                scan_statements(statement.finalbody)
                continue
            if isinstance(statement, ast.Global):
                result.globals.update(statement.names)
                continue
            if isinstance(statement, ast.Nonlocal):
                result.nonlocals.update(statement.names)
                continue
            if isinstance(statement, ast.Delete):
                for target in statement.targets:
                    add_target(
                        target,
                        "delete",
                        statement,
                        None,
                        unit.node_end_offset(statement),
                    )
                continue
            if hasattr(ast, "Match") and isinstance(statement, ast.Match):
                scan_expression(statement.subject)
                conditional_start = len(result.bindings)
                for case in statement.cases:
                    for name_node in ast.walk(case.pattern):
                        if isinstance(name_node, ast.MatchAs) and name_node.name:
                            line, column = unit.node_start(name_node)
                            result.bindings.append(
                                Binding(name_node.name, "assignment", unit, name_node, line, column)
                            )
                    scan_expression(case.guard)
                    scan_statements(case.body)
                mark_conditional_since(conditional_start)
                continue
            for field_name in ("value", "test", "iter", "subject"):
                scan_expression(getattr(statement, field_name, None))

    if isinstance(scope, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda)):
        arguments = scope.args
        all_arguments = list(arguments.posonlyargs) + list(arguments.args) + list(arguments.kwonlyargs)
        if arguments.vararg is not None:
            all_arguments.append(arguments.vararg)
        if arguments.kwarg is not None:
            all_arguments.append(arguments.kwarg)
        for argument in all_arguments:
            line, column = unit.node_start(argument)
            result.bindings.append(Binding(argument.arg, "parameter", unit, argument, line, column))
        if isinstance(scope, ast.Lambda):
            scan_expression(scope.body)
        else:
            scan_statements(scope.body)
    else:
        scan_statements(scope.body)
    return result


class Entity:
    def __init__(
        self,
        kind,
        location=None,
        unit=None,
        node=None,
        module=None,
        owner_class=None,
        class_entity=None,
    ):
        self.kind = kind
        self.location = location
        self.unit = unit
        self.node = node
        self.module = module
        self.owner_class = owner_class
        self.class_entity = class_entity


class ModuleInfo:
    def __init__(self, name, origin, search_locations, is_package):
        self.name = name
        self.origin = origin
        self.search_locations = search_locations
        self.is_package = is_package
        self.unit = None
        self.source_error = None


class StaticResolver:
    def __init__(self, request, units, current_index):
        self.request = request
        self.units = units
        self.current_index = current_index
        self.max_depth = request["maxReexportDepth"]
        self.max_source_bytes = request["maxSourceBytes"]
        self.max_modules = request["maxModules"]
        self.module_cache = {}
        self.active = set()
        roots = [os.getcwd()]
        roots.extend(path for path in sys.path if path and os.path.isdir(path))
        self.search_roots = list(dict.fromkeys(os.path.abspath(path) for path in roots))

    def clicked_chain(self, unit, cursor_offset):
        selected = None
        for index, item in enumerate(unit.tokens):
            if item.type != token.NAME:
                continue
            start = unit.absolute_offset(item.start[0] - 1, item.start[1])
            end = unit.absolute_offset(item.end[0] - 1, item.end[1])
            if start <= cursor_offset < end:
                selected = index
                break
        if selected is None:
            for index, item in enumerate(unit.tokens):
                if item.type != token.NAME:
                    continue
                end = unit.absolute_offset(item.end[0] - 1, item.end[1])
                if cursor_offset == end:
                    selected = index
                    break
        if selected is None:
            return None
        significant = [(index, item) for index, item in enumerate(unit.tokens) if item.type not in IGNORED_TOKENS]
        selected_position = next(
            (index for index, (original, _item) in enumerate(significant) if original == selected),
            None,
        )
        if selected_position is None:
            return None
        chain = [significant[selected_position][1].string]
        position = selected_position
        while position >= 2:
            dot_item = significant[position - 1][1]
            name_item = significant[position - 2][1]
            if dot_item.string != "." or name_item.type != token.NAME:
                break
            chain.insert(0, name_item.string)
            position -= 2
        clicked = significant[selected_position][1]
        lookup_offset = unit.absolute_offset(clicked.start[0] - 1, clicked.start[1])
        return chain, lookup_offset, clicked.string

    def lookup_notebook(self, unit_index, offset, name):
        unit = self.units[unit_index]
        if unit is None:
            return None, False
        chain = unit.scope_chain(offset)
        force_module = False
        for scope in chain:
            data = scope_bindings(unit, scope)
            if not isinstance(scope, ast.Module):
                if name in data.globals:
                    force_module = True
                    continue
                if force_module:
                    continue
                candidates = [
                    binding
                    for binding in data.bindings
                    if binding.name == name and binding.available_offset <= offset
                ]
                if candidates:
                    candidate = max(candidates, key=lambda binding: binding.available_offset)
                    return (None, True) if candidate.conditional else (candidate, False)
                if any(binding.name == name for binding in data.bindings) and name not in data.nonlocals:
                    return None, True
                continue
            candidates = [
                binding
                for binding in data.bindings
                if binding.name == name and binding.available_offset <= offset
            ]
            if candidates:
                candidate = max(candidates, key=lambda binding: binding.available_offset)
                return (None, True) if candidate.conditional else (candidate, False)
        for previous_index in range(unit_index - 1, -1, -1):
            previous = self.units[previous_index]
            if previous is None:
                continue
            data = scope_bindings(previous, previous.tree)
            candidates = [binding for binding in data.bindings if binding.name == name]
            if candidates:
                candidate = max(candidates, key=lambda binding: binding.available_offset)
                return (None, True) if candidate.conditional else (candidate, False)
        return None, False

    def resolve_request(self, cursor_offset):
        unit = self.units[self.current_index]
        clicked = self.clicked_chain(unit, cursor_offset)
        if clicked is None:
            return unresolved("no_symbol", "The cursor is not on a Python identifier.")
        chain, lookup_offset, clicked_symbol = clicked
        binding, blocked = self.lookup_notebook(self.current_index, lookup_offset, chain[0])
        if binding is None:
            message = (
                "The name has no unambiguous preceding lexical binding."
                if blocked
                else "No preceding lexical binding was found."
            )
            return unresolved("not_found", message)
        entity = self.resolve_binding(binding, chain[1:], 0)
        if entity is None or entity.location is None:
            return unresolved("source_unavailable", "The symbol has no statically resolvable source.")
        result = dict(entity.location)
        result["symbol"] = clicked_symbol
        return result

    def resolve_binding(self, binding, tail, depth):
        if depth > self.max_depth or binding.kind == "delete":
            return None
        key = ("binding", id(binding), tuple(tail))
        if key in self.active:
            return None
        self.active.add(key)
        try:
            if binding.kind == "import":
                module = self.module_entity(binding.module)
                return self.follow(module, tail, depth)
            if binding.kind == "from_import":
                module_name = self.absolute_import(binding)
                if module_name is None:
                    return None
                module = self.module_entity(module_name)
                return self.follow(module, [binding.imported] + list(tail), depth + 1)
            if binding.kind == "class":
                entity = Entity(
                    "class",
                    binding.location(),
                    binding.unit,
                    binding.node,
                )
                return self.follow(entity, tail, depth)
            if binding.kind == "function":
                entity = Entity("function", binding.location(), binding.unit, binding.node)
                return self.follow(entity, tail, depth)
            if binding.kind == "assignment" and tail:
                inferred = self.resolve_expression(binding.unit, binding.value, depth)
                return self.follow(inferred, tail, depth)
            if not tail:
                return Entity("binding", binding.location(), binding.unit, binding.node)
            return None
        finally:
            self.active.discard(key)

    def follow(self, entity, tail, depth):
        if entity is None or depth > self.max_depth:
            return None
        if not tail:
            return entity
        if entity.kind == "module":
            return self.resolve_module_attributes(entity.module, list(tail), depth)
        if entity.kind == "class":
            return self.resolve_class_member(entity, list(tail), depth)
        if entity.kind == "instance":
            return self.resolve_class_member(entity.class_entity, list(tail), depth)
        return None

    def resolve_expression(self, unit, expression, depth):
        if expression is None or depth > self.max_depth:
            return None
        if isinstance(expression, ast.Call):
            callable_entity = self.resolve_expression(unit, expression.func, depth)
            if callable_entity is None:
                return None
            if callable_entity.kind == "class":
                return Entity("instance", class_entity=callable_entity)
            if callable_entity.kind == "function" and callable_entity.owner_class is not None:
                if self.is_classmethod(callable_entity) or self.returns_owner(callable_entity):
                    return Entity("instance", class_entity=callable_entity.owner_class)
            return None
        chain = flatten_attribute(expression)
        if not chain:
            return None
        offset = unit.node_start_offset(expression)
        if unit.is_notebook:
            unit_index = self.units.index(unit)
            binding, _blocked = self.lookup_notebook(unit_index, offset, chain[0])
        else:
            binding, ambiguous = self.lookup_external(unit, chain[0], offset)
            if ambiguous:
                return None
        if binding is None:
            return None
        return self.resolve_binding(binding, chain[1:], depth)

    def is_classmethod(self, entity):
        if not isinstance(entity.node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            return False
        for decorator in entity.node.decorator_list:
            chain = flatten_attribute(decorator)
            if chain and chain[-1] == "classmethod":
                return True
        return False

    def returns_owner(self, entity):
        annotation = getattr(entity.node, "returns", None)
        if annotation is None or entity.owner_class is None:
            return False
        owner_name = getattr(entity.owner_class.node, "name", None)
        if isinstance(annotation, ast.Name):
            return annotation.id in ("Self", owner_name)
        if isinstance(annotation, ast.Constant) and isinstance(annotation.value, str):
            return annotation.value in ("Self", owner_name)
        return False

    def resolve_class_member(self, class_entity, tail, depth):
        if not tail or depth > self.max_depth or class_entity.unit is None:
            return class_entity if not tail else None
        key = ("class", id(class_entity.node), tuple(tail))
        if key in self.active:
            return None
        self.active.add(key)
        try:
            data = scope_bindings(class_entity.unit, class_entity.node)
            candidates = [binding for binding in data.bindings if binding.name == tail[0]]
            if candidates:
                binding = max(candidates, key=lambda item: item.available_offset)
                if binding.conditional:
                    return None
                if binding.kind in ("function", "class"):
                    kind = binding.kind
                    entity = Entity(kind, binding.location(), binding.unit, binding.node)
                    if kind == "function":
                        entity.owner_class = class_entity
                    return self.follow(entity, tail[1:], depth)
                if len(tail) == 1:
                    return Entity("member", binding.location(), binding.unit, binding.node)
                inferred = self.resolve_expression(binding.unit, binding.value, depth)
                return self.follow(inferred, tail[1:], depth)
            for base in getattr(class_entity.node, "bases", []):
                base_entity = self.resolve_expression(class_entity.unit, base, depth + 1)
                if base_entity is not None and base_entity.kind == "class":
                    found = self.resolve_class_member(base_entity, tail, depth + 1)
                    if found is not None:
                        return found
            return None
        finally:
            self.active.discard(key)

    def module_entity(self, module_name):
        module = self.find_module(module_name)
        if module is None:
            return None
        location = None
        if module.origin is not None:
            location = {
                "status": "file",
                "path": module.origin,
                "line": 0,
                "column": 0,
                "symbol": module.name.rsplit(".", 1)[-1],
            }
        return Entity("module", location=location, module=module)

    def find_module(self, module_name):
        if not module_name or module_name.startswith("."):
            return None
        if module_name in self.module_cache:
            return self.module_cache[module_name]
        if len(self.module_cache) >= self.max_modules:
            return None
        parts = module_name.split(".")
        paths = self.search_roots
        fullname = ""
        spec = None
        for part in parts:
            fullname = part if not fullname else fullname + "." + part
            try:
                spec = PathFinder.find_spec(fullname, paths)
            except Exception:
                spec = None
            if spec is None:
                self.module_cache[module_name] = None
                return None
            paths = list(spec.submodule_search_locations or [])
        origin = getattr(spec, "origin", None)
        if not isinstance(origin, str) or not origin.lower().endswith((".py", ".pyi")):
            origin = None
        elif not os.path.isfile(origin):
            origin = None
        module = ModuleInfo(
            module_name,
            os.path.abspath(origin) if origin else None,
            list(spec.submodule_search_locations or []),
            spec.submodule_search_locations is not None,
        )
        self.module_cache[module_name] = module
        return module

    def load_module_unit(self, module):
        if module.unit is not None or module.source_error is not None:
            return module.unit
        if module.origin is None:
            module.source_error = "no Python source"
            return None
        try:
            if os.path.getsize(module.origin) > self.max_source_bytes:
                module.source_error = "source is too large"
                return None
            with tokenize.open(module.origin) as stream:
                source = stream.read()
            if len(source.encode("utf-8")) > self.max_source_bytes:
                module.source_error = "source is too large"
                return None
            module.unit = SourceUnit(
                module.name,
                source,
                path=module.origin,
                module_name=module.name,
                is_package=module.is_package,
            )
        except Exception as exception:
            module.source_error = str(exception)
        return module.unit

    def lookup_external(self, unit, name, before_offset=None):
        data = scope_bindings(unit, unit.tree)
        candidates = [binding for binding in data.bindings if binding.name == name]
        if before_offset is not None:
            candidates = [binding for binding in candidates if binding.available_offset <= before_offset]
        if not candidates:
            return None, False
        candidate = max(candidates, key=lambda item: item.available_offset)
        return (None, True) if candidate.conditional else (candidate, False)

    def resolve_module_attributes(self, module, tail, depth):
        if module is None or not tail or depth > self.max_depth:
            return self.module_entity(module.name) if module is not None and not tail else None
        key = ("module", module.name, tuple(tail))
        if key in self.active:
            return None
        self.active.add(key)
        try:
            unit = self.load_module_unit(module)
            if unit is not None:
                binding, ambiguous = self.lookup_external(unit, tail[0])
                if ambiguous:
                    return None
                if binding is not None:
                    return self.resolve_binding(binding, tail[1:], depth)
            submodule = self.module_entity(module.name + "." + tail[0])
            if submodule is not None:
                return self.follow(submodule, tail[1:], depth + 1)
            if unit is not None:
                stars = [
                    binding
                    for binding in scope_bindings(unit, unit.tree).bindings
                    if binding.kind == "star_import" and not binding.conditional
                ]
                for star in reversed(stars):
                    imported_module = self.absolute_import(star)
                    entity = self.module_entity(imported_module) if imported_module else None
                    found = self.follow(entity, tail, depth + 1)
                    if found is not None:
                        return found
            return None
        finally:
            self.active.discard(key)

    def absolute_import(self, binding):
        if not binding.level:
            return binding.module
        unit = binding.unit
        if not unit.module_name:
            return None
        package = unit.module_name if unit.is_package else unit.module_name.rpartition(".")[0]
        if not package:
            return None
        relative = "." * binding.level + (binding.module or "")
        try:
            return importlib.util.resolve_name(relative, package)
        except (ImportError, ValueError):
            return None


def flatten_attribute(node):
    result = []
    current = node
    while isinstance(current, ast.Attribute):
        result.insert(0, current.attr)
        current = current.value
    if isinstance(current, ast.Name):
        result.insert(0, current.id)
        return result
    return None


def validate_request(request):
    if not isinstance(request, dict):
        return "Request must be a JSON object."
    cells = request.get("cells")
    if not isinstance(cells, list) or not cells:
        return "Request cells must be a non-empty list."
    if not isinstance(request.get("currentCellId"), str):
        return "currentCellId must be a string."
    if not isinstance(request.get("cursorCodePointOffset"), int):
        return "cursorCodePointOffset must be an integer."
    for key in ("maxSourceBytes", "maxModules", "maxReexportDepth"):
        if not isinstance(request.get(key), int) or request[key] <= 0:
            return key + " must be a positive integer."
    for cell in cells:
        if not isinstance(cell, dict) or not isinstance(cell.get("id"), str) or not isinstance(cell.get("source"), str):
            return "Every cell must have string id and source fields."
    return None


def main():
    try:
        request = json.load(sys.stdin)
    except Exception as exception:
        emit(unresolved("invalid_request", "Invalid JSON request: " + str(exception)))
        return
    validation_error = validate_request(request)
    if validation_error:
        emit(unresolved("invalid_request", validation_error))
        return
    current_id = request["currentCellId"]
    units = []
    current_index = None
    for index, cell in enumerate(request["cells"]):
        if cell["id"] == current_id:
            current_index = index
        try:
            units.append(SourceUnit(cell["id"], cell["source"]))
        except (SyntaxError, tokenize.TokenError, IndentationError):
            units.append(None)
    if current_index is None:
        emit(unresolved("invalid_request", "The current cell is missing."))
        return
    current = units[current_index]
    if current is None:
        emit(unresolved("invalid_source", "The current cell is not valid Python source."))
        return
    cursor = request["cursorCodePointOffset"]
    if cursor < 0 or cursor > len(current.source):
        emit(unresolved("invalid_request", "The cursor is outside the current cell."))
        return
    try:
        resolver = StaticResolver(request, units, current_index)
        emit(resolver.resolve_request(cursor))
    except Exception as exception:
        emit(unresolved("helper_error", type(exception).__name__ + ": " + str(exception)))


if __name__ == "__main__":
    main()
