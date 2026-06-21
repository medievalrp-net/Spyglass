package net.medievalrp.spyglass.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler.ParamContext;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.junit.jupiter.api.Test;

class ContentParamTest {

    private static Pattern messagePattern(QueryPredicate predicate) {
        assertThat(predicate).isInstanceOf(QueryPredicate.Or.class);
        QueryPredicate.Or or = (QueryPredicate.Or) predicate;
        assertThat(or.predicates()).hasSize(2);
        QueryPredicate first = or.predicates().get(0);
        QueryPredicate second = or.predicates().get(1);
        assertThat(((QueryPredicate.Eq) first).field()).isEqualTo("message");
        assertThat(((QueryPredicate.Eq) second).field()).isEqualTo("commandLine");
        Object value = ((QueryPredicate.Eq) first).value();
        assertThat(value).isInstanceOf(Pattern.class);
        return (Pattern) value;
    }

    @Test
    void literalIsQuotedAndCaseInsensitive() throws Exception {
        Pattern pattern = messagePattern(new ContentParam().parse("content", "how are we today", ctx()));
        // Pattern.quote wraps in \Q...\E so metacharacters are literal.
        assertThat(pattern.pattern()).isEqualTo(Pattern.quote("how are we today"));
        assertThat(pattern.flags() & Pattern.CASE_INSENSITIVE).isNotZero();
    }

    @Test
    void slashDelimitedFormIsCompiledAsRegex() throws Exception {
        Pattern pattern = messagePattern(new ContentParam().parse("content", "/(slur1|slur2)/", ctx()));
        // Regex body is used verbatim (not quoted), case-insensitive.
        assertThat(pattern.pattern()).isEqualTo("(slur1|slur2)");
        assertThat(pattern.flags() & Pattern.CASE_INSENSITIVE).isNotZero();
        assertThat(pattern.matcher("a SLUR2 here").find()).isTrue();
    }

    @Test
    void literalMetacharsAreNotTreatedAsRegex() throws Exception {
        // A bare value with regex metachars stays literal: "a.b" matches "a.b",
        // not "axb".
        Pattern pattern = messagePattern(new ContentParam().parse("content", "a.b", ctx()));
        assertThat(pattern.matcher("a.b").find()).isTrue();
        assertThat(pattern.matcher("axb").find()).isFalse();
    }

    @Test
    void invalidRegexRejected() {
        assertThatThrownBy(() -> new ContentParam().parse("content", "/(unclosed/", ctx()))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("regex");
    }

    @Test
    void emptyValueRejected() {
        assertThatThrownBy(() -> new ContentParam().parse("content", "  ", ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    @Test
    void loneSlashesAreLiteralNotRegex() throws Exception {
        // "//" is too short to be the /regex/ form; treat as a literal "//".
        Pattern pattern = messagePattern(new ContentParam().parse("content", "//", ctx()));
        assertThat(pattern.pattern()).isEqualTo(Pattern.quote("//"));
    }

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}
